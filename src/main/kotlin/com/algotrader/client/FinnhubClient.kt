package com.algotrader.client

import com.algotrader.config.Config
import com.algotrader.model.FundamentalData
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request

private val logger = KotlinLogging.logger {}

class FinnhubClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    /**
     * Temel metrikleri çek: F/K, PD/DD, Market Cap, Beta
     * Sabah bir kez çek ve cache'le — gün içi değişmez
     */
    fun getFundamentals(symbol: String): FundamentalData {
        val url = "${Config.FINNHUB_BASE_URL}/stock/metric" +
                  "?symbol=$symbol&metric=all&token=${Config.FINNHUB_API_KEY}"

        val request = Request.Builder().url(url).get().build()

        return runCatching {
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
        }.getOrElse { e ->
            logger.error(e) { "[$symbol] Fundamental veri hatası" }
            defaultData(symbol)
        }
    }

    /**
     * Tüm watchlist için sabah toplu çek
     */
    fun loadFundamentals(symbols: List<String>): Map<String, FundamentalData> {
        logger.info { "Fundamental veriler yükleniyor: $symbols" }
        return symbols.associateWith { symbol ->
            Thread.sleep(500) // Finnhub rate limit: 60 req/dk
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
