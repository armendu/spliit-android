package app.spliit.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

// Compares the shape of live responses with the recorded fixtures (@Tag("live")).
// A failure lists what changed; when it's expected, `make fixtures` re-records.
@Tag("live")
class ApiContractDriftTest {
    private val baseUrl =
        System.getProperty("spliit.baseUrl")?.ifBlank { null } ?: "http://localhost:3009/"
    private val client = TrpcClient(baseUrl)
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    // The raw body, not via a model: decoding would drop fields nothing declares.
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
                "fallback, see CLAUDE.md on why asking only one name is how this broke before."
        }
    }
}
