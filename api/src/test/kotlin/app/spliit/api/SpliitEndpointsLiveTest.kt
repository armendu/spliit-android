package app.spliit.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

// Live only: `make test-live`, after `make e2e-up`.
@Tag("live")
class SpliitEndpointsLiveTest {
    // localhost:3009 is `make e2e-up`'s default, so this also runs from an IDE.
    private val client = TrpcClient(System.getProperty("spliit.baseUrl")?.ifBlank { null } ?: "http://localhost:3009/")

    @Test
    fun `create a group, add an expense, read the balances, and resolve stats through the fallback`() =
        runBlocking {
            val form = GroupFormValues(
                name = "Live test group ${Instant.now().toEpochMilli()}",
                information = "",
                currency = "$",
                currencyCode = "USD",
                participants = listOf(
                    GroupFormValues.Participant(name = "Ana"),
                    GroupFormValues.Participant(name = "Bruno"),
                ),
            )

            val groupId = client.call(SpliitEndpoints.groupsCreate(form)).groupId
            assertTrue(groupId.isNotBlank())

            val group = client.call(SpliitEndpoints.groupsGet(groupId)).group
            assertNotNull(group)
            val ana = group!!.participants.single { it.name == "Ana" }
            val bruno = group.participants.single { it.name == "Bruno" }

            val expenseForm = ExpenseFormValues(
                title = "Live test expense",
                expenseDate = Instant.now(),
                amount = 1000,
                category = 0,
                paidBy = ana.id,
                paidFor = listOf(
                    ExpenseFormValues.PaidFor(participant = ana.id, shares = 100),
                    ExpenseFormValues.PaidFor(participant = bruno.id, shares = 100),
                ),
                splitMode = SplitMode.EVENLY,
                saveDefaultSplittingOptions = false,
                isReimbursement = false,
                documents = emptyList(),
                recurrenceRule = RecurrenceRule.None,
            )
            val expenseId = client.call(
                SpliitEndpoints.expensesCreate(groupId, expenseForm, participantId = ana.id),
            ).expenseId
            assertTrue(expenseId.isNotBlank())

            val balances = client.call(SpliitEndpoints.balancesList(groupId)).balances
            assertEquals(2, balances.size)
            assertEquals(500, balances.getValue(ana.id).total)
            assertEquals(-500, balances.getValue(bruno.id).total)

            val stats = client.groupStats(groupId, participantId = ana.id)
            assertEquals(1000, stats.totalGroupSpendings)
            assertEquals(1000, stats.totalParticipantSpendings)
        }

    @Test
    fun `scheduling a group's deletion stamps deleteAt and logs it, and restoring undoes it`() = runBlocking {
        val name = "Deletion test ${Instant.now().toEpochMilli()}"
        val groupId = client.call(
            SpliitEndpoints.groupsCreate(
                GroupFormValues(
                    name = name,
                    information = "",
                    currency = "€",
                    currencyCode = "EUR",
                    participants = listOf(GroupFormValues.Participant(name = "Ana")),
                ),
            ),
        ).groupId
        val ana = client.call(SpliitEndpoints.groupsGet(groupId)).group!!.participants.single()

        client.call(SpliitEndpoints.groupsDelete(groupId, name, participantId = ana.id))
        val deleteAt = client.call(SpliitEndpoints.groupsGet(groupId)).group!!.deleteAt
        assertNotNull(deleteAt)
        assertTrue(deleteAt!!.isAfter(Instant.now()))
        assertEquals(deleteAt, client.call(SpliitEndpoints.groupsList(listOf(groupId))).groups.single().deleteAt)

        client.call(SpliitEndpoints.groupsRestore(groupId, participantId = ana.id))
        assertNull(client.call(SpliitEndpoints.groupsGet(groupId)).group!!.deleteAt)

        val logged = client.call(SpliitEndpoints.activitiesList(groupId)).activities.map { it.activityType to it.participantId }
        assertTrue(ActivityType.ScheduleGroupDeletion to ana.id in logged, "log was $logged")
        assertTrue(ActivityType.RestoreGroup to ana.id in logged, "log was $logged")
    }

    // Mirrors the limits in :core's GroupFormDraft and ExpenseFormDraft, which can't be imported here.
    @Test
    fun `the server accepts and refuses at the same lengths the form drafts do`() = runBlocking {
        fun group(name: String = "Limits ${Instant.now().toEpochMilli()}".take(50), information: String = "") =
            GroupFormValues(
                name = name,
                information = information,
                currency = "€",
                currencyCode = "EUR",
                participants = listOf(GroupFormValues.Participant(name = "Ana")),
            )
        suspend fun refused(block: suspend () -> Unit) {
            val error = try {
                block()
                null
            } catch (e: TrpcServerError) {
                e
            }
            assertEquals("BAD_REQUEST", error?.code)
        }

        client.call(SpliitEndpoints.groupsCreate(group(name = "x".repeat(50))))
        refused { client.call(SpliitEndpoints.groupsCreate(group(name = "x".repeat(51)))) }
        refused { client.call(SpliitEndpoints.groupsCreate(group(name = "x"))) }
        client.call(SpliitEndpoints.groupsCreate(group(information = "x".repeat(10_000))))
        refused { client.call(SpliitEndpoints.groupsCreate(group(information = "x".repeat(10_001)))) }

        val groupId = client.call(SpliitEndpoints.groupsCreate(group())).groupId
        val ana = client.call(SpliitEndpoints.groupsGet(groupId)).group!!.participants.single()
        fun expense(title: String = "Dinner", notes: String? = null) = ExpenseFormValues(
            title = title,
            expenseDate = Instant.now(),
            amount = 1000,
            category = 0,
            paidBy = ana.id,
            paidFor = listOf(ExpenseFormValues.PaidFor(participant = ana.id, shares = 100)),
            splitMode = SplitMode.EVENLY,
            saveDefaultSplittingOptions = false,
            isReimbursement = false,
            documents = emptyList(),
            notes = notes,
            recurrenceRule = RecurrenceRule.None,
        )
        client.call(SpliitEndpoints.expensesCreate(groupId, expense(title = "x".repeat(200))))
        refused { client.call(SpliitEndpoints.expensesCreate(groupId, expense(title = "x".repeat(201)))) }
        client.call(SpliitEndpoints.expensesCreate(groupId, expense(notes = "x".repeat(5000))))
        refused { client.call(SpliitEndpoints.expensesCreate(groupId, expense(notes = "x".repeat(5001)))) }
    }
}
