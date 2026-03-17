package com.algotrader.client

import com.algotrader.config.Config
import com.algotrader.model.AlpacaBar
import com.algotrader.model.AlpacaOrderRequest
import com.algotrader.model.AlpacaOrderResponse
import com.algotrader.model.TradeOrder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val logger = KotlinLogging.logger {}

class AlpacaRestClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(url: String) = Request.Builder()
        .url(url)
        .addHeader("APCA-API-KEY-ID", Config.ALPACA_KEY_ID)
        .addHeader("APCA-API-SECRET-KEY", Config.ALPACA_SECRET_KEY)
        .addHeader("Accept", "application/json")

    /**
     * Son N adet 1-dakikalık bar çek (backtesting / indikatör ısınması için)
     */
    fun getHistoricalBars(symbol: String, limit: Int = 100): List<AlpacaBar> {
        val url = "${Config.ALPACA_DATA_REST_URL}/stocks/$symbol/bars" +
                  "?timeframe=1Min&limit=$limit&feed=iex"

        val request = buildRequest(url).get().build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                if (!response.isSuccessful) {
                    logger.error { "Historical bars hatası [$symbol]: ${response.code} $body" }
                    return emptyList()
                }

                val root = json.parseToJsonElement(body).jsonObject
                val bars = root["bars"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonArray ?: return emptyList()

                bars.map { bar ->
                    val obj = bar.jsonObject
                    AlpacaBar(
                        symbol = symbol,
                        open   = obj["o"]!!.jsonPrimitive.double,
                        high   = obj["h"]!!.jsonPrimitive.double,
                        low    = obj["l"]!!.jsonPrimitive.double,
                        close  = obj["c"]!!.jsonPrimitive.double,
                        volume = obj["v"]!!.jsonPrimitive.long,
                        timestamp = obj["t"]!!.jsonPrimitive.content
                    )
                }.also {
                    logger.info { "[$symbol] ${it.size} historical bar yüklendi" }
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "[$symbol] Historical bar çekme hatası" }
            emptyList()
        }
    }

    /**
     * Paper trading emri gönder
     */
    fun submitOrder(order: TradeOrder): AlpacaOrderResponse? {
        val orderRequest = AlpacaOrderRequest(
            symbol   = order.symbol,
            notional = order.notional.toString(),
            side     = order.side.name.lowercase()
        )

        val body = json.encodeToString(orderRequest)
            .toRequestBody(jsonMediaType)

        val request = buildRequest("${Config.ALPACA_BASE_URL}/v2/orders")
            .post(body)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    logger.error { "Order hatası [${order.symbol}]: ${response.code} $responseBody" }
                    return null
                }
                json.decodeFromString<AlpacaOrderResponse>(responseBody).also {
                    logger.info {
                        "✅ Order gönderildi: ${order.side} ${order.symbol} " +
                        "\$${order.notional} → OrderID: ${it.id}"
                    }
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "Order gönderme hatası [${order.symbol}]" }
            null
        }
    }

    /**
     * Açık pozisyonları listele
     */
    fun getPositions(): List<JsonObject> {
        val request = buildRequest("${Config.ALPACA_BASE_URL}/v2/positions").get().build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                json.parseToJsonElement(body).jsonArray
                    .map { it.jsonObject }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Hesap durumu
     */
    fun getAccountInfo(): JsonObject? {
        val request = buildRequest("${Config.ALPACA_BASE_URL}/v2/account").get().build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body).jsonObject
            }
        }.getOrNull()
    }
}
