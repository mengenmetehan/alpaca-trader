package com.algotrader

import com.algotrader.client.AlpacaRestClient
import com.algotrader.client.FinnhubClient
import com.algotrader.config.Config
import com.algotrader.model.FundamentalData
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class ConnectionTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun alpaca() = AlpacaRestClient(baseUrl = baseUrl(), dataBaseUrl = baseUrl())
    private fun finnhub() = FinnhubClient(baseUrl = baseUrl(), rateLimitDelayMs = 0)

    // ── 1. Alpaca Account ────────────────────────────────────────────────────

    @Test
    fun `alpaca account info doner`() {
        server.enqueue(MockResponse()
            .setBody("""{"status":"ACTIVE","portfolio_value":"100000.00","cash":"95000.00"}""")
            .addHeader("Content-Type", "application/json"))

        val account = alpaca().getAccountInfo()

        assertNotNull(account, "Response parse edilemedi")
        assertEquals("ACTIVE", account["status"]?.toString()?.trim('"'))

        val portfolioValue = account["portfolio_value"]?.toString()?.trim('"')?.toDoubleOrNull()
        assertNotNull(portfolioValue, "portfolio_value parse edilemedi")
        assertTrue(portfolioValue >= 0)

        val cash = account["cash"]?.toString()?.trim('"')?.toDoubleOrNull()
        assertNotNull(cash, "cash parse edilemedi")
        assertTrue(cash >= 0)
    }

    // ── 2. Alpaca Historical Bars ────────────────────────────────────────────

    @Test
    fun `alpaca AAPL icin historical bar ceker`() {
        server.enqueue(MockResponse()
            .setBody("""{"bars":[
                {"o":185.0,"h":186.0,"l":184.0,"c":185.5,"v":1000000,"t":"2024-01-02T09:30:00Z"},
                {"o":185.5,"h":187.0,"l":185.0,"c":186.0,"v":1100000,"t":"2024-01-02T09:31:00Z"},
                {"o":186.0,"h":188.0,"l":185.5,"c":187.0,"v":1200000,"t":"2024-01-02T09:32:00Z"},
                {"o":187.0,"h":189.0,"l":186.5,"c":188.0,"v":1300000,"t":"2024-01-02T09:33:00Z"},
                {"o":188.0,"h":190.0,"l":187.5,"c":189.0,"v":1400000,"t":"2024-01-02T09:34:00Z"}
            ],"symbol":"AAPL","next_page_token":null}""")
            .addHeader("Content-Type", "application/json"))

        val bars = alpaca().getHistoricalBars("AAPL", limit = 5)

        assertTrue(bars.isNotEmpty(), "Bar listesi boş")
        assertEquals(5, bars.size)
        bars.forEach { bar ->
            assertEquals("AAPL", bar.symbol)
            assertTrue(bar.open > 0)
            assertTrue(bar.high >= bar.open)
            assertTrue(bar.high >= bar.close)
            assertTrue(bar.low <= bar.open)
            assertTrue(bar.low <= bar.close)
            assertTrue(bar.volume > 0)
            assertTrue(bar.timestamp.isNotBlank())
        }
    }

    @Test
    fun `alpaca her watchlist sembolu icin bar ceker`() {
        val barJson = """{"bars":[{"o":100.0,"h":101.0,"l":99.0,"c":100.5,"v":500000,"t":"2024-01-02T09:30:00Z"}],"next_page_token":null}"""
        repeat(3) {
            server.enqueue(MockResponse().setBody(barJson).addHeader("Content-Type", "application/json"))
        }

        val alpaca = alpaca()
        val symbols = Config.FULL_WATCHLIST.take(3)
        val failures = mutableListOf<String>()

        symbols.forEach { symbol ->
            val bars = alpaca.getHistoricalBars(symbol, limit = 5)
            if (bars.isEmpty()) failures.add(symbol)
        }

        assertTrue(failures.isEmpty(), "Şu semboller için bar parse edilemedi: $failures")
    }

    @Test
    fun `alpaca gecersiz sembol icin bos liste doner`() {
        server.enqueue(MockResponse()
            .setBody("""{"bars":null,"symbol":"INVALID_TICKER_XYZ","next_page_token":null}""")
            .addHeader("Content-Type", "application/json"))

        val bars = alpaca().getHistoricalBars("INVALID_TICKER_XYZ", limit = 1)
        assertTrue(bars.isEmpty(), "Geçersiz sembol için boş liste bekleniyor")
    }

    // ── 3. Alpaca Positions ──────────────────────────────────────────────────

    @Test
    fun `alpaca pozisyon listesi exception firlatmaz`() {
        server.enqueue(MockResponse()
            .setBody("[]")
            .addHeader("Content-Type", "application/json"))

        val positions = alpaca().getPositions()
        assertNotNull(positions)
    }

    // ── 4. Finnhub Fundamentals ──────────────────────────────────────────────

    @Test
    fun `finnhub AAPL icin fundamental veri ceker`() {
        server.enqueue(MockResponse()
            .setBody("""{"metric":{"peBasicExclExtraTTM":28.5,"pbQuarterly":45.2,"marketCapitalization":2800000.0,"beta":1.2},"metricType":"all","symbol":"AAPL"}""")
            .addHeader("Content-Type", "application/json"))

        val fundamental = finnhub().getFundamentals("AAPL")

        assertEquals("AAPL", fundamental.symbol)
        assertNotNull(fundamental.peRatio, "P/E null geldi")
        assertTrue(fundamental.peRatio!! > 0)
        assertTrue(fundamental.isUndervalued, "P/E 28.5 → undervalued olmalı")
    }

    @Test
    fun `finnhub isUndervalued PE 35 altinda true PE 35 ustunde false`() {
        val undervalued = FundamentalData(symbol = "TEST", peRatio = 20.0, pbRatio = null, marketCap = null, beta = null)
        assertTrue(undervalued.isUndervalued, "P/E 20 → undervalued olmalı")

        val overvalued = FundamentalData(symbol = "TEST", peRatio = 50.0, pbRatio = null, marketCap = null, beta = null)
        assertFalse(overvalued.isUndervalued, "P/E 50 → undervalued olmamalı")

        val noData = FundamentalData(symbol = "TEST", peRatio = null, pbRatio = null, marketCap = null, beta = null)
        assertFalse(noData.isUndervalued, "P/E null → undervalued false olmalı")
    }

    @Test
    fun `finnhub watchlist icin toplu fundamental yuklenir`() {
        val body = """{"metric":{"peBasicExclExtraTTM":28.5,"pbQuarterly":2.1,"marketCapitalization":500000.0,"beta":1.0},"symbol":"X"}"""
        repeat(3) {
            server.enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))
        }

        val symbols = Config.FULL_WATCHLIST.take(3)
        val result = finnhub().loadFundamentals(symbols)

        assertEquals(symbols.size, result.size, "Her sembol için bir sonuç bekleniyor")
        symbols.forEach { symbol ->
            assertTrue(result.containsKey(symbol), "$symbol sonuçta yok")
        }
    }
}
