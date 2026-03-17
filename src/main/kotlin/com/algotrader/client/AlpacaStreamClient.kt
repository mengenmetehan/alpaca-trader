package com.algotrader.client

import com.algotrader.config.Config
import com.algotrader.model.AlpacaAuthMsg
import com.algotrader.model.AlpacaBar
import com.algotrader.model.AlpacaSubscribeMsg
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.*

private val logger = KotlinLogging.logger {}

class AlpacaStreamClient(
    private val initialSymbols: List<String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val barChannel = Channel<AlpacaBar>(capacity = Channel.UNLIMITED)

    val barFlow: Flow<AlpacaBar> = barChannel.receiveAsFlow()

    @Volatile private var activeWebSocket: WebSocket? = null
    private val currentSubscriptions = mutableSetOf<String>()

    /**
     * Mevcut abonelikleri yeni sembol listesiyle değiştir (reconnect olmadan).
     * Açık pozisyonlar her zaman activeSymbols içinde gönderilmeli.
     */
    fun resubscribe(newSymbols: List<String>) {
        val ws = activeWebSocket ?: run {
            logger.warn { "resubscribe çağrıldı ama WebSocket bağlı değil" }
            return
        }

        val newSet = newSymbols.toSet()
        val toUnsubscribe = currentSubscriptions - newSet
        val toSubscribe   = newSet - currentSubscriptions

        if (toUnsubscribe.isNotEmpty()) {
            val msg = buildJsonObject {
                put("action", "unsubscribe")
                putJsonArray("bars") { toUnsubscribe.forEach { add(it) } }
            }.toString()
            ws.send(msg)
            currentSubscriptions -= toUnsubscribe
            logger.info { "Unsubscribe: $toUnsubscribe" }
        }

        if (toSubscribe.isNotEmpty()) {
            val msg = buildJsonObject {
                put("action", "subscribe")
                putJsonArray("bars") { toSubscribe.forEach { add(it) } }
            }.toString()
            ws.send(msg)
            currentSubscriptions += toSubscribe
            logger.info { "Subscribe: $toSubscribe" }
        }
    }

    fun connect() {
        val request = Request.Builder()
            .url(Config.ALPACA_DATA_WS_URL)
            .addHeader("APCA-API-KEY-ID", Config.ALPACA_KEY_ID)
            .addHeader("APCA-API-SECRET-KEY", Config.ALPACA_SECRET_KEY)
            .build()

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeWebSocket = webSocket
                logger.info { "WebSocket bağlandı" }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.error(t) { "WebSocket hatası: ${t.message}" }
                // Yeniden bağlan
                Thread.sleep(3000)
                connect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.warn { "WebSocket kapandı: $reason" }
            }
        })
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        runCatching {
            val array = json.parseToJsonElement(text).jsonArray

            array.forEach { element ->
                val obj = element.jsonObject
                when (val msgType = obj["T"]?.jsonPrimitive?.content) {

                    // Bağlantı kuruldu → Auth gönder
                    "connected" -> {
                        logger.info { "Sunucuya bağlandı, auth gönderiliyor..." }
                        val authMsg = json.encodeToString(
                            AlpacaAuthMsg(
                                key = Config.ALPACA_KEY_ID,
                                secret = Config.ALPACA_SECRET_KEY
                            )
                        )
                        webSocket.send(authMsg)
                    }

                    // Auth başarılı → Subscribe ol
                    "success" -> {
                        val msg = obj["msg"]?.jsonPrimitive?.content
                        if (msg == "authenticated") {
                            logger.info { "Auth başarılı, ${initialSymbols.size} sembol subscribe ediliyor..." }
                            val subscribeMsg = buildJsonObject {
                                put("action", "subscribe")
                                putJsonArray("bars") { initialSymbols.forEach { add(it) } }
                            }.toString()
                            webSocket.send(subscribeMsg)
                            currentSubscriptions += initialSymbols
                        }
                    }

                    // 1-dakikalık bar geldi
                    "b" -> {
                        val bar = json.decodeFromJsonElement<AlpacaBar>(element)
                        logger.debug {
                            "[${bar.symbol}] O:${bar.open} H:${bar.high} " +
                            "L:${bar.low} C:${bar.close} V:${bar.volume}"
                        }
                        barChannel.trySend(bar)
                    }

                    "error" -> {
                        logger.error { "Alpaca hata: $obj" }
                    }

                    else -> logger.trace { "Bilinmeyen mesaj tipi: $msgType" }
                }
            }
        }.onFailure { e ->
            logger.error(e) { "Mesaj parse hatası: $text" }
        }
    }

    fun close() {
        barChannel.close()
        client.dispatcher.executorService.shutdown()
    }
}
