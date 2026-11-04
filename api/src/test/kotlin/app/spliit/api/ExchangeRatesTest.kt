package app.spliit.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class ExchangeRatesTest {
    private val server = MockWebServer()
    private lateinit var rates: ExchangeRates
    private val day = LocalDate.of(2026, 10, 30)

    @BeforeEach
    fun start() {
        server.start()
        rates = ExchangeRates(server.url("/").toString())
    }

    @AfterEach
    fun stop() = server.close()

    private fun answer(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `asks Frankfurter v2 for one pair on one day and keeps the published digits`() = runBlocking {
        answer(200, """[{"date":"2026-10-30","base":"USD","quote":"EUR","rate":0.89501}]""")

        val rate = rates.rate(day, from = "USD", to = "EUR")

        assertEquals(ExchangeRate(BigDecimal("0.89501"), day), rate)
        val url = server.takeRequest().url
        assertEquals("/v2/rates", url.encodedPath)
        assertEquals("2026-10-30", url.queryParameter("date"))
        assertEquals("USD", url.queryParameter("base"))
        assertEquals("EUR", url.queryParameter("quotes"))
    }

    @Test
    fun `a rate for another day is returned with that day`() = runBlocking {
        answer(200, """[{"date":"2026-10-29","base":"USD","quote":"EUR","rate":0.895}]""")

        assertEquals(LocalDate.of(2026, 10, 29), rates.rate(day, "USD", "EUR")?.date)
    }

    @Test
    fun `no rate for that day is null, not an error`() = runBlocking {
        answer(200, "[]")

        assertNull(rates.rate(day, "USD", "EUR"))
    }

    @Test
    fun `a currency Frankfurter does not know is null, not an error`() = runBlocking {
        answer(422, """{"status":422,"message":"invalid currency: XYZ"}""")

        assertNull(rates.rate(day, "XYZ", "EUR"))
    }

    @Test
    fun `a server failure or an unreadable answer is an ExchangeRateError`() {
        answer(503, "down")
        answer(200, "<html>not json</html>")

        assertThrows<ExchangeRateError> { runBlocking { rates.rate(day, "USD", "EUR") } }
        assertThrows<ExchangeRateError> { runBlocking { rates.rate(day, "USD", "EUR") } }
    }

    @Test
    fun `an unreachable server is an ExchangeRateError`() {
        server.close()

        assertThrows<ExchangeRateError> { runBlocking { rates.rate(day, "USD", "EUR") } }
    }
}
