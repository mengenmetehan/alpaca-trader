package com.algotrader

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.AlpacaStreamClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.db.DatabaseService
import com.algotrader.model.OrderStatus
import com.algotrader.notification.TelegramClient
import com.algotrader.strategy.TradingStrategy
import kotlinx.coroutines.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    logger.info { "🚀 AlgoTrader başlatılıyor..." }

    val restClient = AlpacaRestClient()
    val finnhub    = FinnhubClient()
    val strategy   = TradingStrategy(restClient, finnhub)
    val telegram   = TelegramClient()
    val db         = DatabaseService()

    // Hesap bilgilerini göster
    restClient.getAccountInfo()?.let { account ->
        logger.info {
            "💰 Paper Hesap → " +
            "Bakiye: \$${account["portfolio_value"]?.toString()?.trim('"') ?: "?"} | " +
            "Nakit: \$${account["cash"]?.toString()?.trim('"') ?: "?"}"
        }
    }

    // 1. Tüm watchlist için fundamental yükle → en iyi N hisseyi seç
    val topSymbols = strategy.loadAndSelectTop(
        Config.FULL_WATCHLIST,
        Config.Strategy.TOP_WATCHLIST_SIZE
    )
    if (topSymbols.isEmpty()) {
        logger.error { "Uygun temel değere sahip hisse bulunamadı, çıkılıyor." }
        return@runBlocking
    }

    // 2. Sadece seçilen hisseler için indikatörleri ısıt
    strategy.warmUp(topSymbols)

    // 3. Sadece bu hisseleri stream et
    val streamClient = AlpacaStreamClient(topSymbols)
    streamClient.connect()
    logger.info { "📡 WebSocket stream aktif | ${topSymbols.size} hisse: $topSymbols" }

    // Bar'ları işle → sinyal üret → emir gönder
    val tradingJob = launch {
        strategy.processBarFlow(streamClient.barFlow)
            .collect { order ->
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

                telegram.notifyOrder(order)
                db.saveOrder(order)
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
        db.close()
    })

    tradingJob.join()
}