package com.algotrader.strategy

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.indicator.IndicatorEngine
import com.algotrader.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
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

    // Long pozisyon takibi
    private val openPositions = mutableSetOf<String>()
    private val entryPrices   = mutableMapOf<String, Double>()

    // Short pozisyon takibi
    private val shortPositions  = mutableSetOf<String>()
    private val shortEntryPrices = mutableMapOf<String, Double>()

    /**
     * Tüm watchlist için fundamental yükle, PE'ye göre sırala, en iyi topN'i döndür.
     * Fundamentals cache'e alınır — warmUp için tekrar yüklenmez.
     */
    fun loadAndSelectTop(symbols: List<String>, topN: Int): List<String> {
        logger.info { "Fundamental tarama başlıyor: ${symbols.size} sembol..." }
        fundamentals = finnhubClient.loadFundamentals(symbols)

        val top = fundamentals.entries
            .filter { (_, f) -> f.isUndervalued }
            .sortedBy { (_, f) -> f.peRatio!! }
            .take(topN)

        if (top.isEmpty()) {
            logger.error { "Hiç uygun temel değer bulunamadı!" }
            return emptyList()
        }

        logger.info { "🏆 En iyi ${top.size} hisse seçildi:" }
        top.forEachIndexed { i, (symbol, f) ->
            logger.info { "  ${i + 1}. $symbol → PE: ${"%.1f".format(f.peRatio)} | PB: ${f.pbRatio?.let { "%.2f".format(it) } ?: "N/A"}" }
        }

        return top.map { it.key }
    }

    /**
     * Verilen semboller için historical bar ile indikatörleri ısıt.
     * Fundamentals önceden yüklenmiş olmalı (loadAndSelectTop çağrıldıktan sonra).
     */
    fun warmUp(symbols: List<String>) {
        logger.info { "İndikatörler ısındırılıyor: $symbols" }
        symbols.forEach { symbol ->
            val engine = IndicatorEngine(symbol)
            engines[symbol] = engine
            val bars = restClient.getHistoricalBars(symbol, limit = 150)
            bars.forEach { bar -> engine.feed(bar) }
            logger.info { "[$symbol] ${engine.barCount()} bar ile ısındı" }
        }
        engines.values.forEach { it.setLive() }
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

        // --- Short pozisyon yönetimi ---
        if (bar.symbol in shortPositions) {
            val entryPrice  = shortEntryPrices[bar.symbol]!!
            val stopPrice   = entryPrice * (1 + Config.Strategy.STOP_LOSS_PCT)    // fiyat yukarı giderse zarar
            val targetPrice = entryPrice * (1 - Config.Strategy.TAKE_PROFIT_PCT)  // fiyat aşağı giderse kâr

            // 1. Stop loss: bar içinde high bu seviyenin üzerine çıktı mı?
            if (bar.high >= stopPrice) {
                shortPositions.remove(bar.symbol)
                shortEntryPrices.remove(bar.symbol)
                engine.feed(bar)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.COVER,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "SHORT_SL giriş:${"%.2f".format(entryPrice)} " +
                               "tetik:${"%.2f".format(stopPrice)} " +
                               "kayıp:+${"%.2f".format((stopPrice - entryPrice) / entryPrice * 100)}%"
                ).also { logger.warn { "🛑 SHORT STOP LOSS: ${bar.symbol} | ${it.reason}" } }
            }

            // 2. Take profit: bar içinde low bu seviyenin altına indi mi?
            if (bar.low <= targetPrice) {
                shortPositions.remove(bar.symbol)
                shortEntryPrices.remove(bar.symbol)
                engine.feed(bar)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.COVER,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "SHORT_TP giriş:${"%.2f".format(entryPrice)} " +
                               "hedef:${"%.2f".format(targetPrice)} " +
                               "kazanç:+${"%.2f".format((entryPrice - targetPrice) / entryPrice * 100)}%"
                ).also { logger.info { "🎯 SHORT TAKE PROFIT: ${bar.symbol} | ${it.reason}" } }
            }

            // 3. Teknik kapama: BUY sinyali
            val signal = engine.feed(bar) ?: return null
            if (signal.isBuySignal) {
                shortPositions.remove(bar.symbol)
                shortEntryPrices.remove(bar.symbol)
                return TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.COVER,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    price    = bar.close,
                    reason   = "SHORT_COVER RSI:${"%.1f".format(signal.rsi)} " +
                               "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                               "BB:${signal.bollingerPosition}"
                ).also { logger.info { "🔵 SHORT COVER: ${bar.symbol} @ ${bar.close} | ${it.reason}" } }
            }
            return null
        }

        // --- BUY / SHORT sinyali (pozisyon yok) ---
        val signal = engine.feed(bar) ?: return null
        val fundamental = fundamentals[bar.symbol]

        // BUY sinyali → long aç
        if (signal.isBuySignal) {
            if (fundamental?.isUndervalued != true) {
                logger.info {
                    "[${bar.symbol}] BUY sinyali var ama temel veri uygun değil " +
                    "(PE: ${fundamental?.peRatio ?: "null"}), atlandı"
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

        // SELL sinyali → short aç
        if (signal.isSellSignal) {
            shortPositions.add(bar.symbol)
            shortEntryPrices[bar.symbol] = bar.close

            return TradeOrder(
                symbol   = bar.symbol,
                side     = OrderSide.SHORT,
                notional = Config.Strategy.ORDER_NOTIONAL,
                price    = bar.close,
                reason   = "RSI:${"%.1f".format(signal.rsi)} " +
                           "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                           "BB:${signal.bollingerPosition} " +
                           "VolRatio:${"%.2f".format(signal.volumeRatio)}"
            ).also {
                logger.info {
                    "🔴 SHORT ORDER: ${bar.symbol} @ ${bar.close} | " +
                    "SL:${"%.2f".format(bar.close * (1 + Config.Strategy.STOP_LOSS_PCT))} " +
                    "TP:${"%.2f".format(bar.close * (1 - Config.Strategy.TAKE_PROFIT_PCT))} | " +
                    it.reason
                }
            }
        }

        return null
    }

    fun getOpenPositions(): Set<String> = openPositions.toSet()

}
