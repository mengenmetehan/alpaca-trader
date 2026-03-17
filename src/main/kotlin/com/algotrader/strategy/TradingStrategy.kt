package com.algotrader.strategy

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.indicator.IndicatorEngine
import com.algotrader.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class TradingStrategy(
    private val restClient: AlpacaRestClient,
    private val finnhubClient: FinnhubClient
) {
    // Sembol başına indikatör motoru
    private val engines = mutableMapOf<String, IndicatorEngine>()

    // Temel veriler (sabah bir kez yüklenir)
    private var fundamentals = mapOf<String, FundamentalData>()

    // Açık pozisyon takibi (aynı sembolde çift pozisyon açmamak için)
    private val openPositions = mutableSetOf<String>()

    // Sembol başına giriş fiyatı (stop loss hesabı için)
    private val entryPrices = mutableMapOf<String, Double>()

    /**
     * Sabah başlangıcında çağır:
     * 1. Fundamental verileri yükle
     * 2. Historical bar ile indikatörleri ısıt
     */
    fun initialize(symbols: List<String>) {
        logger.info { "Strateji başlatılıyor: $symbols" }

        // 1. Fundamentals
        fundamentals = finnhubClient.loadFundamentals(symbols)

        // 2. Her sembol için IndicatorEngine oluştur ve historical bar ile ısıt
        symbols.forEach { symbol ->
            val engine = IndicatorEngine(symbol)
            engines[symbol] = engine

            val historicalBars = restClient.getHistoricalBars(symbol, limit = 150)
            historicalBars.forEach { bar -> engine.feed(bar) }

            logger.info { "[$symbol] ${engine.barCount()} bar ile ısındı" }
        }

        logger.info { "Strateji hazır. Canlı stream bekleniyor..." }
    }

    /**
     * Canlı bar flow'unu işle → alım/satım kararları ver
     */
    fun processBarFlow(barFlow: Flow<AlpacaBar>): Flow<TradeOrder> =
        barFlow.mapNotNull { bar -> processBar(bar) }

    private fun processBar(bar: AlpacaBar): TradeOrder? {
        val engine = engines.getOrPut(bar.symbol) { IndicatorEngine(bar.symbol) }

        // --- Açık pozisyon yönetimi ---
        // SL/TP için engine.feed'e gerek yok; bar'ın low/high ile fiyat eşiği kontrol edilir.
        if (bar.symbol in openPositions) {
            val entryPrice  = entryPrices[bar.symbol]!!
            val stopPrice   = entryPrice * (1 - Config.Strategy.STOP_LOSS_PCT)
            val targetPrice = entryPrice * (1 + Config.Strategy.TAKE_PROFIT_PCT)

            // 1. Stop loss: bar içinde low bu seviyeye indi mi?
            if (bar.low <= stopPrice) {
                openPositions.remove(bar.symbol)
                entryPrices.remove(bar.symbol)
                engine.feed(bar)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.SELL,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "STOP_LOSS giriş:${"%.2f".format(entryPrice)} " +
                               "tetik:${"%.2f".format(stopPrice)} " +
                               "kayıp:${"%.2f".format((stopPrice - entryPrice) / entryPrice * 100)}%"
                ).also { logger.warn { "🛑 STOP LOSS: ${bar.symbol} | ${it.reason}" } }
            }

            // 2. Take profit: bar içinde high bu seviyeye çıktı mı?
            if (bar.high >= targetPrice) {
                openPositions.remove(bar.symbol)
                entryPrices.remove(bar.symbol)
                engine.feed(bar)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.SELL,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "TAKE_PROFIT giriş:${"%.2f".format(entryPrice)} " +
                               "hedef:${"%.2f".format(targetPrice)} " +
                               "kazanç:+${"%.2f".format((targetPrice - entryPrice) / entryPrice * 100)}%"
                ).also { logger.info { "🎯 TAKE PROFIT: ${bar.symbol} | ${it.reason}" } }
            }

            // 3. Teknik satış sinyali
            val signal = engine.feed(bar) ?: return null
            if (signal.isSellSignal) {
                openPositions.remove(bar.symbol)
                entryPrices.remove(bar.symbol)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.SELL,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "RSI:${"%.1f".format(signal.rsi)} " +
                               "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                               "BB:${signal.bollingerPosition}"
                ).also { logger.info { "🔴 SELL ORDER: ${bar.symbol} @ ${bar.close} | ${it.reason}" } }
            }
            return null
        }

        // --- BUY sinyali ---
        val signal = engine.feed(bar) ?: return null
        val fundamental = fundamentals[bar.symbol]

        if (!signal.isBuySignal) return null

        if (fundamental?.isUndervalued == false) {
            logger.info {
                "[${bar.symbol}] BUY sinyali var ama F/K çok yüksek (${fundamental.peRatio}), atlandı"
            }
            return null
        }

        // Haber yoğunluğu filtresi: son 1 saatte çok fazla haber varsa volatilite riski yüksek
        val newsCount = finnhubClient.getRecentNewsCount(bar.symbol)
        if (newsCount > Config.Strategy.MAX_NEWS_COUNT) {
            logger.info {
                "[${bar.symbol}] BUY sinyali var ama son ${Config.Strategy.NEWS_WINDOW_MINUTES}dk'da " +
                "$newsCount haber var (limit: ${Config.Strategy.MAX_NEWS_COUNT}), atlandı"
            }
            return null
        }

        openPositions.add(bar.symbol)
        entryPrices[bar.symbol] = bar.close

        return TradeOrder(
            symbol   = bar.symbol,
            side     = OrderSide.BUY,
            notional = Config.Strategy.ORDER_NOTIONAL,
            price    = bar.close,
            reason   = "RSI:${"%.1f".format(signal.rsi)} " +
                       "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                       "BB:${signal.bollingerPosition} " +
                       "VolRatio:${"%.2f".format(signal.volumeRatio)}"
        ).also {
            logger.info {
                "🟢 BUY ORDER: ${bar.symbol} @ ${bar.close} | " +
                "SL:${"%.2f".format(bar.close * (1 - Config.Strategy.STOP_LOSS_PCT))} " +
                "TP:${"%.2f".format(bar.close * (1 + Config.Strategy.TAKE_PROFIT_PCT))} | " +
                it.reason
            }
        }
    }

    fun getOpenPositions(): Set<String> = openPositions.toSet()

    /**
     * Yeni sayfaya geç:
     * - Daha önce görülmemiş semboller için fundamentals + historical bar yükle
     * - Engine cache kalıcıdır; aynı sembol tekrar gelince sıfırdan ısınmaya gerek yok
     */
    suspend fun rotatePage(newSymbols: List<String>) = withContext(Dispatchers.IO) {
        logger.info { "🔄 Sayfa değişiyor → ${newSymbols.size} sembol: $newSymbols" }

        // Sadece cache'de olmayan semboller için fundamentals çek
        val unknownSymbols = newSymbols.filter { it !in fundamentals }
        if (unknownSymbols.isNotEmpty()) {
            val newFundamentals = finnhubClient.loadFundamentals(unknownSymbols)
            fundamentals = fundamentals + newFundamentals
        }

        // Sadece daha önce ısınmamış semboller için historical bar yükle
        newSymbols.filter { it !in engines }.forEach { symbol ->
            val engine = IndicatorEngine(symbol)
            engines[symbol] = engine
            val bars = restClient.getHistoricalBars(symbol, limit = 150)
            bars.forEach { bar -> engine.feed(bar) }
            logger.info { "[$symbol] ${engine.barCount()} bar ile ısındı" }
        }

        logger.info { "✅ Sayfa hazır. Cache'deki toplam engine: ${engines.size}" }
    }
}
