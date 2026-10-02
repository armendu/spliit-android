package app.spliit.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
}
