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

    // Tüm semboller 20'lik sayfalara bölünür
    val pages = Config.FULL_WATCHLIST.chunked(Config.Strategy.PAGE_SIZE)
    logger.info { "📋 Toplam ${Config.FULL_WATCHLIST.size} sembol → ${pages.size} sayfa (${Config.Strategy.PAGE_SIZE}'er)" }

    var pageIndex = 0
    val firstPage = pages[pageIndex]

    val restClient   = AlpacaRestClient()
    val finnhub      = FinnhubClient()
    val streamClient = AlpacaStreamClient(firstPage)
    val strategy     = TradingStrategy(restClient, finnhub)
    val telegram     = TelegramClient()
    val db           = DatabaseService()

    // Hesap bilgilerini göster
    restClient.getAccountInfo()?.let { account ->
        logger.info {
            "💰 Paper Hesap → " +
            "Bakiye: \$${account["portfolio_value"]?.toString()?.trim('"') ?: "?"} | " +
            "Nakit: \$${account["cash"]?.toString()?.trim('"') ?: "?"}"
        }
    }

    // İlk sayfa: fundamentals + historical bar ısınması
    strategy.initialize(firstPage)

    // WebSocket stream başlat (ilk sayfa ile)
    streamClient.connect()
    logger.info { "📡 WebSocket stream aktif | Sayfa 1/${pages.size}: $firstPage" }

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

                telegram.notifyOrder(order)
                db.saveOrder(order)
            }
    }

    // Her 2 dakikada bir sonraki sayfaya geç
    val rotationJob = launch {
        while (isActive) {
            delay(Config.Strategy.PAGE_ROTATION_MS)
            pageIndex = (pageIndex + 1) % pages.size
            val newPage = pages[pageIndex]

            // Açık pozisyonlu semboller sayfadan çıksa bile stream'de kalsın
            val openSymbols = strategy.getOpenPositions().toList()
            val activeSymbols = (newPage + openSymbols).distinct()

            logger.info { "🔄 Sayfa ${pageIndex + 1}/${pages.size} → $newPage" }
            if (openSymbols.isNotEmpty()) {
                logger.info { "📌 Açık pozisyon korunuyor: $openSymbols" }
            }

            strategy.rotatePage(activeSymbols)
            streamClient.resubscribe(activeSymbols)
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
            rotationJob.cancel()
        }
        streamClient.close()
        db.close()
    })

    tradingJob.join()
}
