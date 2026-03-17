package com.algotrader.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Alpaca WebSocket mesajları ---

@Serializable
data class AlpacaAuthMsg(
    val action: String = "auth",
    val key: String,
    val secret: String
)

@Serializable
data class AlpacaSubscribeMsg(
    val action: String = "subscribe",
    val bars: List<String> = emptyList(),
    val trades: List<String> = emptyList(),
    val quotes: List<String> = emptyList()
)

@Serializable
data class AlpacaBar(
    @SerialName("T") val type: String = "b",
    @SerialName("S") val symbol: String,
    @SerialName("o") val open: Double,
    @SerialName("h") val high: Double,
    @SerialName("l") val low: Double,
    @SerialName("c") val close: Double,
    @SerialName("v") val volume: Long,
    @SerialName("t") val timestamp: String
)

@Serializable
data class AlpacaTrade(
    @SerialName("T") val type: String = "t",
    @SerialName("S") val symbol: String,
    @SerialName("p") val price: Double,
    @SerialName("s") val size: Int,
    @SerialName("t") val timestamp: String
)

// --- Teknik analiz sinyali ---

data class TechnicalSignal(
    val symbol: String,
    val rsi: Double,
    val macdHistogram: Double,
    val bollingerPosition: BollingerPosition,
    val volumeRatio: Double,       // Mevcut hacim / 20-bar ortalama hacim
    val price: Double,
    val timestamp: String
) {
    enum class BollingerPosition { ABOVE_UPPER, INSIDE, BELOW_LOWER }

    // Alım sinyali: RSI aşırı satım + MACD pozitif dönüş + Bollinger alt band
    val isBuySignal: Boolean get() =
        rsi < 35.0 &&
        macdHistogram > 0 &&
        bollingerPosition == BollingerPosition.BELOW_LOWER &&
        volumeRatio > 1.5   // Hacim normalin 1.5 katı üzerinde

    // Satım sinyali: RSI aşırı alım + MACD negatif + Bollinger üst band
    val isSellSignal: Boolean get() =
        rsi > 65.0 &&
        macdHistogram < 0 &&
        bollingerPosition == BollingerPosition.ABOVE_UPPER &&
        volumeRatio > 1.5
}

// --- Temel analiz verisi (Finnhub) ---

data class FundamentalData(
    val symbol: String,
    val peRatio: Double?,
    val pbRatio: Double?,
    val marketCap: Double?,
    val beta: Double?
) {
    // Temel filtre: makul değerleme
    val isUndervalued: Boolean get() =
        peRatio != null && peRatio > 0 && peRatio < 35.0
}

// --- Order ---

enum class OrderSide { BUY, SELL }
enum class OrderStatus { PENDING, FILLED, CANCELLED, FAILED }

data class TradeOrder(
    val symbol: String,
    val side: OrderSide,
    val notional: Double,   // USD cinsinden (adet hesabı için referans)
    val price: Double,      // Sinyal anındaki bar kapanış fiyatı
    val reason: String,
    var qty: Int = 0,                    // submitOrder tarafından set edilir
    var status: OrderStatus = OrderStatus.PENDING,
    var alpacaOrderId: String? = null
)

// --- Alpaca REST Order request/response ---

@Serializable
data class AlpacaOrderRequest(
    val symbol: String,
    val qty: String,        // Tam adet (notional yerine — tüm hisseler destekler)
    val side: String,
    @SerialName("type") val orderType: String = "market",
    @SerialName("time_in_force") val timeInForce: String = "day"
)

@Serializable
data class AlpacaOrderResponse(
    val id: String,
    val status: String,
    val symbol: String,
    val side: String,
    @SerialName("filled_avg_price") val filledAvgPrice: String? = null
)
