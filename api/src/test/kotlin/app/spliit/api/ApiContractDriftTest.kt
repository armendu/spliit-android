package app.spliit.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What changed, when the server changes underneath us.
 *
 * The decode tests next door answer "does the response still fit our models" — a yes/no that
 * says nothing useful when the answer is no, and that stays green for the most dangerous kind of
 * change there is: a field that quietly disappears from a response our models declare optional.
 * `groups.stats.get` becoming `groups.stats.overview` is the version of this that already
 * shipped, and every pinned test in this repo stayed green through it.
 *
 * So this one compares the **shape** of a live response against the recorded fixture and reports
 * the difference field by field: what is gone, what is new, what changed type. See [JsonShape]
 * for the two kinds of difference it deliberately ignores (empty lists and nulls), both of which
 * are about how much data exists rather than about the contract.
 *
 * It is `@Tag("live")`, so `make test` never runs it. `make test-live` does, and CI's nightly
 * `upstream-drift` job points that at spliit.app — which is the only place this can catch
 * anything, since the job that builds pull requests pins the server image on purpose.
 *
 * **A failure here is not a broken build.** It is a list of things to go and look at. If the
 * change is expected, `make fixtures` re-records, and the diff in that commit is the changelog.
 */
@Tag("live")
class ApiContractDriftTest {

    private val baseUrl =
        System.getProperty("spliit.baseUrl")?.ifBlank { null } ?: "http://localhost:3009/"
    private val client = TrpcClient(baseUrl)
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The raw body, without going through a model.
     *
     * Decoding first would defeat the point: kotlinx-serialization drops fields nothing declares,
     * so a new field on the server would be invisible to exactly the check meant to find it.
     * [TrpcClient.buildRequest] is reused so the envelope, the percent-encoding and the base-URL
     * handling are the ones the app really uses rather than a second implementation that could
     * drift on its own.
     */
    private fun raw(procedure: TrpcProcedure<*, *>): String =
        http.newCall(client.buildRequest(procedure)).execute().use { response ->
            val body = response.body.string()
            assertTrue(response.isSuccessful) { "${procedure.path} answered ${response.code}: ${body.take(300)}" }
            body
        }

    private fun compare(fixtureName: String, procedure: TrpcProcedure<*, *>) {
        val recorded = JsonShape.of(Json.parseToJsonElement(Fixture.text(fixtureName)))
        val live = JsonShape.of(Json.parseToJsonElement(raw(procedure)))
        val differences = JsonShape.diff(recorded, live)
        assertTrue(differences.isEmpty()) { JsonShape.report(procedure.path, differences) }
    }

    @Test
    fun `every recorded response still has the shape the fixture recorded`() = runBlocking {
        // Real data first, and created here rather than assumed: several of the procedures below
        // answer with empty lists on a group that has nothing in it, and an empty list is the one
        // thing this comparison cannot learn anything from.
        val form = GroupFormValues(
            name = "Contract drift ${Instant.now().toEpochMilli()}",
            information = "A group the drift check made.",
            currency = "$",
            currencyCode = "USD",
            participants = listOf(
                GroupFormValues.Participant(name = "Ana"),
                GroupFormValues.Participant(name = "Bruno"),
            ),
        )
        val groupId = client.call(SpliitEndpoints.groupsCreate(form)).groupId
        val group = checkNotNull(client.call(SpliitEndpoints.groupsGet(groupId)).group)
        val ana = group.participants.single { it.name == "Ana" }
        val bruno = group.participants.single { it.name == "Bruno" }

        val expenseId = client.call(
            SpliitEndpoints.expensesCreate(
                groupId = groupId,
                values = ExpenseFormValues(
                    title = "Drift check expense",
                    expenseDate = Instant.now(),
                    amount = 1234,
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
                    notes = "A note, so `notes` is a string on at least one expense.",
                    recurrenceRule = RecurrenceRule.None,
                ),
                participantId = ana.id,
            ),
        ).expenseId

        compare("categories.list", SpliitEndpoints.categoriesList())
        compare("groups.get", SpliitEndpoints.groupsGet(groupId))
        compare("groups.getDetails", SpliitEndpoints.groupsGetDetails(groupId))
        compare("groups.list", SpliitEndpoints.groupsList(listOf(groupId)))
        compare("groups.expenses.list", SpliitEndpoints.expensesList(groupId, limit = 20))
        compare("groups.expenses.get", SpliitEndpoints.expensesGet(groupId, expenseId))
        compare("groups.balances.list", SpliitEndpoints.balancesList(groupId))
        compare("groups.activities.list", SpliitEndpoints.activitiesList(groupId))
        compare("groups.stats.overview", SpliitEndpoints.statsOverview(groupId, ana.id))
    }

    /**
     * The other half of drift: a procedure that stops existing.
     *
     * A rename shows up here as an error rather than as a shape difference, because the request
     * never gets a payload to compare. `groups.stats.get` is the one this repo has already been
     * bitten by, and it is *expected* to be gone on a current server — so this asserts only that
     * asking produces a clear answer either way, and prints which.
     */
    @Test
    fun `the stats procedures report which of the two names this server answers to`() = runBlocking {
        val names = mutableListOf<String>()
        val form = GroupFormValues(
            name = "Stats names ${Instant.now().toEpochMilli()}",
            information = "",
            currency = "$",
            currencyCode = "USD",
            participants = listOf(GroupFormValues.Participant(name = "Ana")),
        )
        val groupId = client.call(SpliitEndpoints.groupsCreate(form)).groupId

        for (procedure in listOf(
            "groups.stats.overview" to SpliitEndpoints.statsOverview(groupId, null),
            "groups.stats.get" to SpliitEndpoints.statsGet(groupId, null),
        )) {
            val answered = http.newCall(client.buildRequest(procedure.second)).execute()
                .use { it.isSuccessful }
            if (answered) names += procedure.first
        }

        println("This server answers to: ${names.joinToString()}")
        assertTrue(names.isNotEmpty()) {
            "Neither groups.stats.overview nor groups.stats.get answered. If upstream has " +
                "renamed the procedure again, SpliitEndpoints needs the new name adding to the " +
                "fallback — see CLAUDE.md on why asking only one name is how this broke before."
        }
    }
}
