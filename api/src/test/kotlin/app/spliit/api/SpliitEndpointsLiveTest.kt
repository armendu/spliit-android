package app.spliit.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A real round trip against a running Spliit instance.
 *
 * `make test` never runs this, :api's build.gradle.kts excludes `@Tag("live")` there, and
 * `make test-live` runs only it, after `make e2e-up`. Tagged rather than named `*LiveTest` so a
 * live test can sit beside the mocked tests for the same procedure instead of in a file of its
 * own; see the Makefile and build.gradle.kts for the wiring.
 */
@Tag("live")
class SpliitEndpointsLiveTest {

    // The Makefile passes -Pspliit.baseUrl, forwarded as a system property by :api's `testLive`
    // task; localhost:3009 is `make e2e-up`'s own default, kept here so this class also runs
    // directly from an IDE without the property set.
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
            // Ana paid the whole $10.00, split evenly, she's owed half, Bruno owes half.
            assertEquals(500, balances.getValue(ana.id).total)
            assertEquals(-500, balances.getValue(bruno.id).total)

            // The e2e image serves only `overview`, so this is the case where the fallback's
            // first attempt succeeds outright, real-world confirmation that the happy path
            // this client will hit for almost everyone actually resolves against a live server,
            // complementing MockWebServer's coverage of the fallback itself in
            // SpliitEndpointsTest.
            val stats = client.groupStats(groupId, participantId = ana.id)
            assertEquals(1000, stats.totalGroupSpendings)
            assertEquals(1000, stats.totalParticipantSpendings)
        }
}
