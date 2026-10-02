package app.spliit.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonShapeTest {
    private fun shape(json: String) = JsonShape.of(Json.parseToJsonElement(json))
    private fun diff(recorded: String, live: String) = JsonShape.diff(shape(recorded), shape(live))

    @Test
    fun `identical documents have no differences`() {
        assertTrue(diff("""{"a":1,"b":"x"}""", """{"a":2,"b":"y"}""").isEmpty())
    }

    @Test
    fun `a removed field is named`() {
        val differences = diff("""{"a":1,"b":"x"}""", """{"a":1}""")
        assertEquals(listOf("b"), differences.map { it.path })
        assertTrue(differences.single().describe.contains("no longer sends it"))
    }

    @Test
    fun `a new field is named`() {
        val differences = diff("""{"a":1}""", """{"a":1,"recurring":{"count":0}}""")
        assertEquals(listOf("recurring", "recurring.count"), differences.map { it.path })
    }

    @Test
    fun `a field that changes type is named with both types`() {
        val differences = diff("""{"total":1416}""", """{"total":"1416.67"}""")
        assertEquals(listOf("total"), differences.map { it.path })
        assertTrue(differences.single().describe.contains("was NUMBER, is now STRING"))
    }

    @Test
    fun `array positions do not matter, only the union of what rows carry`() {
        val recorded = """{"rows":[{"id":"1"},{"id":"2","note":"x"}]}"""
        val live = """{"rows":[{"id":"2","note":"y"},{"id":"1"}]}"""
        assertTrue(diff(recorded, live).isEmpty())
    }

    @Test
    fun `a list that happens to be empty is not every field going missing`() {
        val recorded = """{"activities":[{"id":"1","activityType":"EXPENSE_CREATED"}]}"""
        val live = """{"activities":[]}"""
        assertTrue(diff(recorded, live).isEmpty())
        assertTrue(diff(live, recorded).isEmpty())
    }

    @Test
    fun `null is data rather than a type, in either direction`() {
        assertTrue(diff("""{"notes":null}""", """{"notes":"something"}""").isEmpty())
        assertTrue(diff("""{"notes":"something"}""", """{"notes":null}""").isEmpty())
    }

    @Test
    fun `a field nested inside a list is still found`() {
        val differences = diff(
            """{"expenses":[{"paidBy":{"id":"p1","name":"Ana"}}]}""",
            """{"expenses":[{"paidBy":{"id":"p1"}}]}""",
        )
        assertEquals(listOf("expenses[].paidBy.name"), differences.map { it.path })
    }

    @Test
    fun `the report names the procedure and every difference`() {
        val differences = diff("""{"a":1,"b":2}""", """{"a":"one","c":3}""")
        val report = JsonShape.report("groups.stats.overview", differences)
        assertTrue(report.contains("groups.stats.overview"))
        assertTrue(report.contains("a: was NUMBER, is now STRING"))
        assertTrue(report.contains("b:"))
        assertTrue(report.contains("c:"))
        assertTrue(report.contains("make fixtures"))
    }

    @Test
    fun `the recorded fixtures all parse into a shape`() {
        for (name in RECORDED_FIXTURES) {
            val shape = shape(Fixture.text(name))
            assertTrue(shape.isNotEmpty(), "$name produced an empty shape")
            assertTrue(shape.containsKey("result.data.json") || shape.containsKey("error.json"), name)
        }
    }

    @Test
    fun `superjson's meta is not part of the contract`() {
        val recorded = """
            {"result":{"data":{"json":{"expenses":[{"id":"1"}]},
            "meta":{"values":{"expenses":{"0":{"expenseDate":["Date"]},"1":{"expenseDate":["Date"]}}}}}}}
        """.trimIndent()
        val live = """{"result":{"data":{"json":{"expenses":[{"id":"1"}]}}}}"""
        assertTrue(diff(recorded, live).isEmpty())
    }

    @Test
    fun `a real change is still reported when meta is present`() {
        val recorded = """{"result":{"data":{"json":{"total":1},"meta":{"values":{}}}}}"""
        val live = """{"result":{"data":{"json":{"total":"1"},"meta":{"values":{}}}}}"""
        assertEquals(listOf("result.data.json.total"), diff(recorded, live).map { it.path })
    }

    @Test
    fun `a dictionary keyed by participant id compares by its values, not its keys`() {
        val recorded = """{"result":{"data":{"json":{"balances":{"aaa":{"total":100,"paid":100,"paidFor":0}}}}}}"""
        val live = """{"result":{"data":{"json":{"balances":{"zzz":{"total":-50,"paid":0,"paidFor":50}}}}}}"""
        assertTrue(diff(recorded, live).isEmpty())
    }

    @Test
    fun `a field disappearing from inside that dictionary is still reported`() {
        val recorded = """{"result":{"data":{"json":{"balances":{"aaa":{"total":100,"paid":100}}}}}}"""
        val live = """{"result":{"data":{"json":{"balances":{"zzz":{"total":100}}}}}}"""
        assertEquals(
            listOf("result.data.json.balances{}.paid"),
            diff(recorded, live).map { it.path },
        )
    }

    private companion object {
        val RECORDED_FIXTURES = listOf(
            "categories.list",
            "groups.activities.list",
            "groups.balances.list",
            "groups.expenses.get",
            "groups.expenses.list",
            "groups.get",
            "groups.getDetails",
            "groups.list",
            "groups.stats.overview",
        )
    }
}
