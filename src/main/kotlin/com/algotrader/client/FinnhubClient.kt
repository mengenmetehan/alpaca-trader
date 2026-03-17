package com.algotrader.client

import com.algotrader.config.Config
import com.algotrader.model.FundamentalData
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private const val RATE_LIMIT_DELAY_MS = 1100L  // 60 req/dk = 1 req/sn, +100ms güvenlik marjı
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 2000L

class FinnhubClient {
    private val json = Json { ignoreUnknownKeys = true }
    // connectionPool: stale connection reuse sorununu önlemek için keep-alive süresi kısaltıldı
    private val client = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    /**
     * Temel metrikleri çek: F/K, PD/DD, Market Cap, Beta.
     * EOFException (stale connection) için MAX_RETRIES kadar otomatik tekrar dener.
     */
    fun getFundamentals(symbol: String): FundamentalData {
        val url = "${Config.FINNHUB_BASE_URL}/stock/metric" +
                  "?symbol=$symbol&metric=all&token=${Config.FINNHUB_API_KEY}"

        repeat(MAX_RETRIES) { attempt ->
            val result = runCatching {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use defaultData(symbol)
                    if (!response.isSuccessful) {
                        logger.warn { "Finnhub hatası [$symbol]: ${response.code}" }
                        return@use defaultData(symbol)
                    }

                    val metric = json.parseToJsonElement(body)
                        .jsonObject["metric"]?.jsonObject
                        ?: return@use defaultData(symbol)

                    FundamentalData(
                        symbol    = symbol,
                        peRatio   = metric["peBasicExclExtraTTM"]?.jsonPrimitive?.doubleOrNull,
                        pbRatio   = metric["pbQuarterly"]?.jsonPrimitive?.doubleOrNull,
                        marketCap = metric["marketCapitalization"]?.jsonPrimitive?.doubleOrNull,
                        beta      = metric["beta"]?.jsonPrimitive?.doubleOrNull
                    ).also {
                        logger.info {
                            "[$symbol] Fundamentals → " +
                            "P/E: ${it.peRatio?.let { pe -> "%.1f".format(pe) } ?: "N/A"} | " +
                            "P/B: ${it.pbRatio?.let { pb -> "%.2f".format(pb) } ?: "N/A"} | " +
                            "Beta: ${it.beta?.let { b -> "%.2f".format(b) } ?: "N/A"}"
                        }
                    }
                }
            }

            result.onSuccess { data -> return data }
            result.onFailure { e ->
                if (e is EOFException && attempt < MAX_RETRIES - 1) {
                    logger.warn { "[$symbol] EOFException (stale connection), ${attempt + 1}. deneme, ${RETRY_DELAY_MS}ms bekleniyor..." }
                    Thread.sleep(RETRY_DELAY_MS)
                } else {
                    logger.error(e) { "[$symbol] Fundamental veri hatası (deneme ${attempt + 1}/$MAX_RETRIES)" }
                }
            }
        }

        return defaultData(symbol)
    }

    /**
     * Tüm watchlist için toplu çek.
     * Rate limit: 60 req/dk → istekler arası 1100ms bekleme.
     */
    fun loadFundamentals(symbols: List<String>): Map<String, FundamentalData> {
        logger.info { "Fundamental veriler yükleniyor: $symbols" }
        return symbols.associateWith { symbol ->
            Thread.sleep(RATE_LIMIT_DELAY_MS)
            getFundamentals(symbol)
        }.also {
            val eligible = it.values.count { f -> f.isUndervalued }
            logger.info { "Fundamental filtre sonucu: ${symbols.size} sembolden $eligible tanesi uygun" }
        }
    }

    private fun defaultData(symbol: String) = FundamentalData(
        symbol = symbol,
        peRatio = null,
        pbRatio = null,
        marketCap = null,
        beta = null
    )
}
