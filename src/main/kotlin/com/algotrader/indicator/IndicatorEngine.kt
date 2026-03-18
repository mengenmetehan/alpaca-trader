package com.algotrader.indicator

import com.algotrader.config.Config
import com.algotrader.model.AlpacaBar
import com.algotrader.model.TechnicalSignal
import mu.KotlinLogging
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.helpers.VolumeIndicator
import org.ta4j.core.num.DecimalNum
import java.time.Duration
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger {}

/**
 * Sembol başına indikatör motoru.
 * Her yeni bar geldiğinde feed() çağır → sinyal al.
 */
class IndicatorEngine(val symbol: String) {

    // ta4j bar serisi — max 200 bar tutar (bellek yönetimi)
    private val series = BaseBarSeries(symbol).apply {
        maximumBarCount = 200
    }

    private val cfg = Config.Strategy

    // --- İndikatörler ---
    private val close  = ClosePriceIndicator(series)
    private val volume = VolumeIndicator(series)

    private val rsi    = RSIIndicator(close, cfg.RSI_PERIOD)

    private val macd   = MACDIndicator(close, cfg.MACD_FAST, cfg.MACD_SLOW)
    private val signal = EMAIndicator(macd, cfg.MACD_SIGNAL)

    private val bbMiddle = BollingerBandsMiddleIndicator(
        SMAIndicator(close, cfg.BOLLINGER_PERIOD)
    )
    private val bbStd  = StandardDeviationIndicator(close, cfg.BOLLINGER_PERIOD)
    private val bbUpper = BollingerBandsUpperIndicator(
        bbMiddle, bbStd, DecimalNum.valueOf(cfg.BOLLINGER_MULTIPLIER)
    )
    private val bbLower = BollingerBandsLowerIndicator(
        bbMiddle, bbStd, DecimalNum.valueOf(cfg.BOLLINGER_MULTIPLIER)
    )

    private val volumeSma = SMAIndicator(volume, cfg.VOLUME_MA_PERIOD)

    // Bar sayacı — indikatörlerin yeterince ısınıp ısınmadığını kontrol etmek için
    private var barCount = 0

    // Warmup bitti mi? false → DEBUG log, true → INFO log
    private var isLive = false

    fun setLive() { isLive = true }

    /**
     * Yeni bar ekle ve sinyal hesapla.
     * Yeterli bar yoksa null döner.
     */
    fun feed(bar: AlpacaBar): TechnicalSignal? {
        addBar(bar)
        barCount++

        // En az MACD_SLOW + MACD_SIGNAL bar olmadan hesaplama yapma
        val minBars = cfg.MACD_SLOW + cfg.MACD_SIGNAL + 5
        if (barCount < minBars) {
            logger.debug { "[$symbol] Isınıyor: $barCount/$minBars bar" }
            return null
        }

        val idx = series.endIndex

        return runCatching {
            val rsiVal    = rsi.getValue(idx).doubleValue()
            val macdVal   = macd.getValue(idx).doubleValue()
            val signalVal = signal.getValue(idx).doubleValue()
            val macdHist  = macdVal - signalVal

            val price     = close.getValue(idx).doubleValue()
            val upperBand = bbUpper.getValue(idx).doubleValue()
            val lowerBand = bbLower.getValue(idx).doubleValue()

            val currentVol = volume.getValue(idx).doubleValue()
            val avgVol     = volumeSma.getValue(idx).doubleValue()
            val volumeRatio = if (avgVol > 0) currentVol / avgVol else 1.0

            val bbPosition = when {
                price > upperBand -> TechnicalSignal.BollingerPosition.ABOVE_UPPER
                price < lowerBand -> TechnicalSignal.BollingerPosition.BELOW_LOWER
                else              -> TechnicalSignal.BollingerPosition.INSIDE
            }

            TechnicalSignal(
                symbol           = symbol,
                rsi              = rsiVal,
                macdHistogram    = macdHist,
                bollingerPosition = bbPosition,
                volumeRatio      = volumeRatio,
                price            = price,
                timestamp        = bar.timestamp
            ).also { sig ->
                val logLine = "[$symbol] RSI: ${"%.1f".format(rsiVal)} | " +
                    "MACD: ${"%.4f".format(macdHist)} | " +
                    "BB: ${bbPosition.name} | " +
                    "Vol: ${"%.2f".format(volumeRatio)}" +
                    when {
                        sig.isBuySignal  -> " → 🟢 BUY SİNYALİ"
                        sig.isSellSignal -> " → 🔴 SELL SİNYALİ"
                        else             -> ""
                    }

                when {
                    isLive && (sig.isBuySignal || sig.isSellSignal) -> logger.info { logLine }
                    isLive && (rsiVal < 42.0 || rsiVal > 58.0) -> logger.info { logLine }
                    else -> logger.debug { logLine }
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "[$symbol] İndikatör hesaplama hatası" }
            null
        }
    }

    private fun addBar(bar: AlpacaBar) {
        val time = ZonedDateTime.parse(bar.timestamp)
        val taBar = BaseBar(
            Duration.ofMinutes(1),
            time,
            DecimalNum.valueOf(bar.open),
            DecimalNum.valueOf(bar.high),
            DecimalNum.valueOf(bar.low),
            DecimalNum.valueOf(bar.close),
            DecimalNum.valueOf(bar.volume),
            DecimalNum.valueOf(0)
        )
        series.addBar(taBar)
    }

    fun barCount(): Int = barCount
}
