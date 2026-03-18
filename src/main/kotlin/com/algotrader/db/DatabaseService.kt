package com.algotrader.db

import com.algotrader.config.Config
import com.algotrader.model.TradeOrder
import mu.KotlinLogging
import java.sql.DriverManager
import java.sql.Connection

private val logger = KotlinLogging.logger {}

class DatabaseService {
    private val enabled = Config.DATABASE_URL != null
    private var connection: Connection? = null

    init {
        if (enabled) {
            runCatching {
                connection = DriverManager.getConnection(toJdbcUrl(Config.DATABASE_URL!!))
                createTableIfNotExists()
                logger.info { "PostgreSQL bağlantısı kuruldu" }
            }.onFailure { e ->
                logger.error(e) { "PostgreSQL bağlantısı kurulamadı, DB kaydı devre dışı" }
                connection = null
            }
        }
    }

    fun saveOrder(order: TradeOrder) {
        val conn = connection ?: return

        runCatching {
            conn.prepareStatement("""
                INSERT INTO orders (symbol, side, qty, price, notional, reason, status, alpaca_order_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, order.symbol)
                stmt.setString(2, order.side.name)
                stmt.setInt   (3, order.qty)
                stmt.setDouble(4, order.price)
                stmt.setDouble(5, order.notional)
                stmt.setString(6, order.reason)
                stmt.setString(7, order.status.name)
                stmt.setString(8, order.alpacaOrderId)
                stmt.executeUpdate()
            }
            logger.info { "💾 DB kaydedildi: ${order.side} ${order.symbol} @ ${order.price} [${order.status}]" }
        }.onFailure { e ->
            logger.error(e) { "DB kayıt hatası: ${order.symbol}" }
            // Bağlantı kopmuşsa yeniden bağlan
            if (!isAlive()) reconnect()
        }
    }

    fun close() {
        connection?.close()
    }

    private fun createTableIfNotExists() {
        connection?.createStatement()?.use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id               SERIAL PRIMARY KEY,
                    symbol           VARCHAR(10)  NOT NULL,
                    side             VARCHAR(4)   NOT NULL,
                    qty              INT          NOT NULL,
                    price            DOUBLE PRECISION NOT NULL,
                    notional         DOUBLE PRECISION NOT NULL,
                    reason           TEXT,
                    status           VARCHAR(10),
                    alpaca_order_id  VARCHAR(50),
                    created_at       TIMESTAMPTZ  DEFAULT NOW()
                )
            """.trimIndent())
        }
    }

    private fun isAlive(): Boolean = runCatching {
        connection?.isValid(2) == true
    }.getOrDefault(false)

    private fun reconnect() {
        runCatching {
            connection?.close()
            connection = DriverManager.getConnection(toJdbcUrl(Config.DATABASE_URL!!))
            logger.info { "PostgreSQL yeniden bağlandı" }
        }.onFailure { e -> logger.error(e) { "PostgreSQL yeniden bağlantı başarısız" } }
    }

    /** postgresql://user:pass@host:5432/db  →  jdbc:postgresql://host:5432/db?user=...&password=... */
    private fun toJdbcUrl(url: String): String {
        val noScheme = url.removePrefix("postgresql://")
        val atIdx    = noScheme.indexOf('@')
        val userInfo = noScheme.substring(0, atIdx)          // user:pass
        val hostDb   = noScheme.substring(atIdx + 1)         // host:port/db
        val (user, pass) = userInfo.split(":", limit = 2)
        return "jdbc:postgresql://$hostDb?user=$user&password=$pass&sslmode=require"
    }
}