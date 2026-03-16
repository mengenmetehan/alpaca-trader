package com.algotrader

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.model.FundamentalData
import kotlin.test.*

/**
 * Gerçek API'lere bağlanan entegrasyon testleri.
 * Çalıştırmadan önce Config.kt'deki (veya env) key'lerin geçerli olduğundan emin ol.
 *
 * Çalıştırma: ./gradlew test
 */
class ConnectionTest {

    private val alpaca = AlpacaRestClient()
    private val finnhub = FinnhubClient()

    // ── 1. Alpaca Account ────────────────────────────────────────────────────

    @Test
    fun `alpaca account info doner`() {
        val account = alpaca.getAccountInfo()

        assertNotNull(account, "Alpaca API bağlantısı başarısız — Key ID / Secret kontrol edilmeli")

        val status = account["status"]?.toString()?.trim('"')
        assertEquals("ACTIVE", status, "Hesap status ACTIVE olmalı, gelen: $status")

        val portfolioValue = account["portfolio_value"]?.toString()?.trim('"')?.toDoubleOrNull()
        assertNotNull(portfolioValue, "portfolio_value parse edilemedi")
        assertTrue(portfolioValue >= 0, "Portfolio value negatif olamaz")

        val cash = account["cash"]?.toString()?.trim('"')?.toDoubleOrNull()
        assertNotNull(cash, "cash parse edilemedi")
        assertTrue(cash >= 0, "Cash negatif olamaz")

        println("💰 Portfolio: $$portfolioValue | Cash: $$cash")
    }

    // ── 2. Alpaca Historical Bars ────────────────────────────────────────────

    @Test
    fun `alpaca AAPL icin historical bar ceker`() {
        val bars = alpaca.getHistoricalBars("AAPL", limit = 5)

        assertTrue(bars.isNotEmpty(), "AAPL için bar verisi gelmedi")
        assertTrue(bars.size <= 5, "İstenen limitten fazla bar geldi: ${bars.size}")

        bars.forEach { bar ->
            assertEquals("AAPL", bar.symbol)
            assertTrue(bar.open > 0, "Open price sıfır veya negatif olamaz")
            assertTrue(bar.high >= bar.open, "High, open'dan küçük olamaz")
            assertTrue(bar.high >= bar.close, "High, close'dan küçük olamaz")
            assertTrue(bar.low <= bar.open, "Low, open'dan büyük olamaz")
            assertTrue(bar.low <= bar.close, "Low, close'dan büyük olamaz")
            assertTrue(bar.volume > 0, "Volume sıfır olamaz")
            assertTrue(bar.timestamp.isNotBlank(), "Timestamp boş olamaz")
        }

        println("📈 AAPL son bar: O=${bars.last().open} H=${bars.last().high} " +
                "L=${bars.last().low} C=${bars.last().close} @ ${bars.last().timestamp}")
    }

    @Test
    fun `alpaca her watchlist sembolu icin bar ceker`() {
        // Geçmiş veri çeker (limit=5 → 5 dakikalık geçmiş), piyasa saatinden bağımsız
        val symbols = Config.WATCHLIST.take(3)
        val failures = mutableListOf<String>()

        symbols.forEach { symbol ->
            val bars = alpaca.getHistoricalBars(symbol, limit = 5)
            if (bars.isEmpty()) failures.add(symbol)
            else println("✅ $symbol → son kapanış: ${bars.last().close} @ ${bars.last().timestamp}")
            Thread.sleep(300)
        }

        assertTrue(failures.isEmpty(), "Şu semboller için bar gelmedi: $failures")
    }

    @Test
    fun `alpaca gecersiz sembol icin bos liste doner`() {
        val bars = alpaca.getHistoricalBars("INVALID_TICKER_XYZ", limit = 1)
        assertTrue(bars.isEmpty(), "Geçersiz sembol için boş liste bekleniyor")
    }

    // ── 3. Alpaca Positions ──────────────────────────────────────────────────

    @Test
    fun `alpaca pozisyon listesi exception firlatmaz`() {
        val positions = alpaca.getPositions()
        // Boş olabilir, ama exception olmamalı
        println("📊 Açık pozisyon sayısı: ${positions.size}")
    }

    // ── 4. Finnhub Fundamentals ──────────────────────────────────────────────

    @Test
    fun `finnhub AAPL icin fundamental veri ceker`() {
        val fundamental = finnhub.getFundamentals("AAPL")

        assertEquals("AAPL", fundamental.symbol)

        // AAPL için P/E her zaman dolu olmalı
        assertNotNull(fundamental.peRatio, "AAPL P/E ratio null geldi — Finnhub key kontrol edilmeli")
        assertTrue(fundamental.peRatio > 0, "P/E sıfır veya negatif olamaz")

        println("📌 AAPL P/E: ${"%.2f".format(fundamental.peRatio)} | " +
                "P/B: ${fundamental.pbRatio?.let { "%.2f".format(it) } ?: "N/A"} | " +
                "Beta: ${fundamental.beta?.let { "%.2f".format(it) } ?: "N/A"} | " +
                "Undervalued: ${fundamental.isUndervalued}")
    }

    @Test
    fun `finnhub isUndervalued PE 35 altinda true PE 35 ustunde false`() {
        // isUndervalued hesaplamasını doğrudan model üzerinde test et
        val undervalued = FundamentalData(
            symbol = "TEST", peRatio = 20.0, pbRatio = null, marketCap = null, beta = null
        )
        assertTrue(undervalued.isUndervalued, "P/E 20 → undervalued olmalı")

        val overvalued = FundamentalData(
            symbol = "TEST", peRatio = 50.0, pbRatio = null, marketCap = null, beta = null
        )
        assertFalse(overvalued.isUndervalued, "P/E 50 → undervalued olmamalı")

        val noData = FundamentalData(
            symbol = "TEST", peRatio = null, pbRatio = null, marketCap = null, beta = null
        )
        assertFalse(noData.isUndervalued, "P/E null → undervalued false olmalı")
    }

    @Test
    fun `finnhub watchlist icin toplu fundamental yuklenir`() {
        val symbols = Config.WATCHLIST.take(3)
        val result = finnhub.loadFundamentals(symbols)

        assertEquals(symbols.size, result.size, "Her sembol için bir sonuç bekleniyor")
        symbols.forEach { symbol ->
            assertTrue(result.containsKey(symbol), "$symbol sonuçta yok")
            println("✅ $symbol → P/E: ${result[symbol]?.peRatio?.let { "%.1f".format(it) } ?: "N/A"}")
        }
    }
}
