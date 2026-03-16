package com.algotrader.config

object Config {
    // Alpaca Paper Trading credentials
    // https://app.alpaca.markets → Paper Trading → API Keys
    val ALPACA_KEY_ID: String = System.getenv("ALPACA_KEY_ID")
        ?: error("ALPACA_KEY_ID environment variable tanımlı değil")
    val ALPACA_SECRET_KEY: String = System.getenv("ALPACA_SECRET_KEY")
        ?: error("ALPACA_SECRET_KEY environment variable tanımlı değil")

    // Finnhub (ücretsiz fundamental data)
    // https://finnhub.io → Dashboard → API Key
    val FINNHUB_API_KEY: String = System.getenv("FINNHUB_API_KEY")
        ?: error("FINNHUB_API_KEY environment variable tanımlı değil")

    // Alpaca Paper endpoints (FREE - gerçek para yok)
    const val ALPACA_BASE_URL = "https://paper-api.alpaca.markets"
    const val ALPACA_DATA_WS_URL = "wss://stream.data.alpaca.markets/v2/iex"
    const val ALPACA_DATA_REST_URL = "https://data.alpaca.markets/v2"

    // Finnhub REST
    const val FINNHUB_BASE_URL = "https://finnhub.io/api/v1"

    // İzlenecek semboller (Nasdaq / Dow Jones bileşenleri)
    val WATCHLIST = listOf("AAPL", "MSFT", "NVDA", "GOOGL", "AMZN", "TSLA", "META")

    // Strateji parametreleri
    object Strategy {
        const val RSI_PERIOD = 14
        const val RSI_OVERSOLD = 30.0
        const val RSI_OVERBOUGHT = 70.0

        const val MACD_FAST = 12
        const val MACD_SLOW = 26
        const val MACD_SIGNAL = 9

        const val BOLLINGER_PERIOD = 20
        const val BOLLINGER_MULTIPLIER = 2.0

        const val VOLUME_MA_PERIOD = 20

        // Temel filtre: sadece bu değerlerin altındaki F/K ile trade et
        const val MAX_PE_RATIO = 35.0

        // Paper order boyutu (USD)
        const val ORDER_NOTIONAL = 1000.0
    }
}
