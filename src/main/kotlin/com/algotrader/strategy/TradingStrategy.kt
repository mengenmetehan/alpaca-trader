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

    // Açık pozisyon takibi (aynı sembolde çift pozisyon açmamak için)
    private val openPositions = mutableSetOf<String>()

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
        val signal = engine.feed(bar) ?: return null

        val fundamental = fundamentals[bar.symbol]

        return when {
            // --- BUY sinyali ---
            signal.isBuySignal && bar.symbol !in openPositions -> {
                // Temel filtre: F/K makul olmalı (veya veri yoksa geç)
                if (fundamental?.isUndervalued == false) {
                    logger.info {
                        "[${bar.symbol}] BUY sinyali var ama " +
                        "F/K çok yüksek (${fundamental.peRatio}), atlandı"
                    }
                    return null
                }

                openPositions.add(bar.symbol)

                TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.BUY,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    reason   = "RSI:${"%.1f".format(signal.rsi)} " +
                               "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                               "BB:${signal.bollingerPosition} " +
                               "VolRatio:${"%.2f".format(signal.volumeRatio)}"
                ).also {
                    logger.info { "🟢 BUY ORDER: ${bar.symbol} @ ${bar.close} | ${it.reason}" }
                }
            }

            // --- SELL sinyali ---
            signal.isSellSignal && bar.symbol in openPositions -> {
                openPositions.remove(bar.symbol)

                TradeOrder(
                    symbol   = bar.symbol,
                    side     = OrderSide.SELL,
                    notional = Config.Strategy.ORDER_NOTIONAL,
                    reason   = "RSI:${"%.1f".format(signal.rsi)} " +
                               "MACD:${if (signal.macdHistogram > 0) "↑" else "↓"} " +
                               "BB:${signal.bollingerPosition}"
                ).also {
                    logger.info { "🔴 SELL ORDER: ${bar.symbol} @ ${bar.close} | ${it.reason}" }
                }
            }

            else -> null
        }
    }

    fun getOpenPositions(): Set<String> = openPositions.toSet()
}
