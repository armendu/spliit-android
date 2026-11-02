package app.spliit.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate

/** One unit of the base currency in the quote currency, as published for [date]. */
public data class ExchangeRate(public val rate: BigDecimal, public val date: LocalDate)

public class ExchangeRateError(message: String) : Exception(message)

/** Daily reference rates from Frankfurter, the source the Spliit web app uses. */
public class ExchangeRates(
    private val baseUrl: String = "https://api.frankfurter.dev/",
    private val httpClient: OkHttpClient = SHARED_HTTP_CLIENT,
) {
    /**
     * The rate from [from] to [to] for [date], or null when Frankfurter has none: an unknown
     * currency, or a day it has no rate for. A rate may be for a nearby day; compare [ExchangeRate.date].
     */
    public suspend fun rate(date: LocalDate, from: String, to: String): ExchangeRate? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v2/rates")
            .addQueryParameter("date", date.toString())
            .addQueryParameter("base", from)
            .addQueryParameter("quotes", to)
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()

        val response = try {
            httpClient.await(request)
        } catch (cause: IOException) {
            throw ExchangeRateError(cause.message ?: "network error")
        }
        response.use { resp ->
            // 422 is Frankfurter's answer to a currency it doesn't know.
            if (resp.code == 422) return@withContext null
            if (!resp.isSuccessful) throw ExchangeRateError("HTTP ${resp.code}")
            val body = try {
                resp.body.string()
            } catch (cause: IOException) {
                throw ExchangeRateError(cause.message ?: "failed reading the response body")
            }
            parse(body, to)
        }
    }

    private fun parse(body: String, quote: String): ExchangeRate? = try {
        Json.parseToJsonElement(body).jsonArray
            .map { it as JsonObject }
            .firstOrNull { it["quote"]?.jsonPrimitive?.content == quote }
            ?.let {
                // From the JSON text, not a double, so the published digits are kept exactly.
                ExchangeRate(
                    rate = BigDecimal(it.getValue("rate").jsonPrimitive.content),
                    date = LocalDate.parse(it.getValue("date").jsonPrimitive.content),
                )
            }
    } catch (cause: RuntimeException) {
        throw ExchangeRateError("unexpected response: ${cause.message}")
    }
}
