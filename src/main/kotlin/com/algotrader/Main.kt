package com.algotrader

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.AlpacaStreamClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.model.OrderStatus
import com.algotrader.strategy.TradingStrategy
import kotlinx.coroutines.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    logger.info { "🚀 AlgoTrader başlatılıyor..." }
    logger.info { "📋 Watchlist: ${Config.WATCHLIST}" }

    val restClient   = AlpacaRestClient()
    val finnhub      = FinnhubClient()
    val streamClient = AlpacaStreamClient(Config.WATCHLIST)
    val strategy     = TradingStrategy(restClient, finnhub)

    // Hesap bilgilerini göster
    restClient.getAccountInfo()?.let { account ->
        logger.info {
            "💰 Paper Hesap → " +
            "Bakiye: \$${account["portfolio_value"]?.toString()?.trim('"') ?: "?"} | " +
            "Nakit: \$${account["cash"]?.toString()?.trim('"') ?: "?"}"
        }
    }

    // Stratejiyi başlat: fundamentals + historical bar ısınması
    strategy.initialize(Config.WATCHLIST)

    // WebSocket stream başlat
    streamClient.connect()
    logger.info { "📡 WebSocket stream aktif, bar bekleniyor..." }

    // Bar'ları işle → sinyal üret → emir gönder
    val tradingJob = launch {
        strategy.processBarFlow(streamClient.barFlow)
            .collect { order ->
                // Paper trading emrini gönder
                val response = restClient.submitOrder(order)

                order.status = if (response != null) {
                    order.alpacaOrderId = response.id
                    OrderStatus.FILLED
                } else {
                    OrderStatus.FAILED
                }

                logger.info {
                    "Order sonucu: ${order.symbol} ${order.side} → ${order.status}" +
                    (order.alpacaOrderId?.let { " [ID: $it]" } ?: "")
                }
            }
    }

    // Pozisyon özeti her 5 dakikada bir logla
    val monitorJob = launch {
        while (isActive) {
            delay(5 * 60 * 1000L)
            val positions = restClient.getPositions()
            if (positions.isNotEmpty()) {
                logger.info { "📊 Açık pozisyonlar (${positions.size}):" }
                positions.forEach { pos ->
                    logger.info {
                        "  ${pos["symbol"]} → " +
                        "Adet: ${pos["qty"]} | " +
                        "Ort. Maliyet: \$${pos["avg_entry_price"]} | " +
                        "P&L: \$${pos["unrealized_pl"]}"
                    }
                }
            } else {
                logger.info { "📊 Açık pozisyon yok" }
            }
        }
    }

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Kapatılıyor..." }
        runBlocking {
            tradingJob.cancel()
            monitorJob.cancel()
        }
        streamClient.close()
    })

    tradingJob.join()
}
