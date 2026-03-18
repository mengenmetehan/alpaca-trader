package com.algotrader.notification

import com.algotrader.config.Config
import com.algotrader.model.OrderSide
import com.algotrader.model.OrderStatus
import com.algotrader.model.TradeOrder
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val logger = KotlinLogging.logger {}

class TelegramClient(
    private val apiBaseUrl: String = "https://api.telegram.org",
    private val token: String? = Config.TELEGRAM_BOT_TOKEN,
    private val chatId: String? = Config.TELEGRAM_CHAT_ID
) {
    private val client = OkHttpClient()
    private val enabled = token != null && chatId != null

    fun notifyOrder(order: TradeOrder) {
        if (!enabled) return

        val emoji = when {
            order.side == OrderSide.BUY                        -> "🟢"
            order.side == OrderSide.COVER                      -> "🔵"
            order.reason.startsWith("STOP_LOSS")               -> "🛑"
            order.reason.startsWith("SHORT_SL")                -> "🛑"
            order.reason.startsWith("TAKE_PROFIT")             -> "🎯"
            order.reason.startsWith("SHORT_TP")                -> "🎯"
            order.side == OrderSide.SHORT                      -> "🔴"
            else                                               -> "🔴"
        }

        val statusLine = if (order.status == OrderStatus.FILLED)
            "✅ Gerçekleşti | ID: ${order.alpacaOrderId}"
        else
            "❌ Başarısız"

        val text = buildString {
            appendLine("$emoji *${order.side} — ${order.symbol}*")
            appendLine("📊 ${order.qty} adet @ ~\$${order.price.format(2)} (~\$${"%.0f".format(order.qty * order.price)})")
            appendLine("📝 ${order.reason}")
            appendLine(statusLine)
        }.trim()

        send(text)
    }

    fun notifyNewsFilter(symbol: String, newsCount: Int, windowMinutes: Int) {
        if (!enabled) return
        send("📰 *$symbol* — BUY sinyali var ama son ${windowMinutes}dk'da $newsCount haber var, atlandı")
    }

    private fun send(text: String) {
        val tok = token   ?: return
        val cid = chatId  ?: return

        val body = """{"chat_id":"$cid","text":"$text","parse_mode":"Markdown"}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$apiBaseUrl/bot$tok/sendMessage")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Telegram hata: ${response.code} ${response.body?.string()}" }
                }
            }
        }.onFailure { e -> logger.warn(e) { "Telegram gönderilemedi" } }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}