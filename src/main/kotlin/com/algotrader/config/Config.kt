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

    // Dow Jones 30 + Nasdaq-100 birleşimi (tekrar edenler çıkarıldı)
    val FULL_WATCHLIST = listOf(
        // Dow Jones 30
        "AAPL", "MSFT", "NVDA", "AMZN", "UNH", "GS", "HD", "CAT", "AMGN", "MCD",
        "V", "CRM", "BA", "HON", "AXP", "TRV", "JPM", "IBM", "JNJ", "WMT",
        "PG", "CVX", "MMM", "KO", "DIS", "MRK", "CSCO", "VZ", "NKE", "DOW",
        // Nasdaq-100 (Dow'da olmayanlar)
        "META", "GOOGL", "GOOG", "TSLA", "AVGO", "COST", "NFLX", "AMD", "QCOM", "ADBE",
        "TXN", "INTU", "CMCSA", "AMAT", "BKNG", "SBUX", "ISRG", "ADI", "REGN", "MDLZ",
        "LRCX", "VRTX", "GILD", "MU", "PANW", "KLAC", "SNPS", "CDNS", "MELI", "ORLY",
        "CTAS", "FTNT", "ADSK", "NXPI", "MNST", "ABNB", "MAR", "PCAR", "KDP", "AEP",
        "IDXX", "MCHP", "ROST", "PAYX", "BIIB", "EA", "FAST", "WDAY", "ODFL", "DXCM",
        "CTSH", "MRNA", "TEAM", "ZS", "CRWD", "DDOG", "GEHC", "ON", "TTWO", "CEG",
        "BKR", "CCEP", "ROP", "VRSK", "ANSS", "DLTR", "TMUS", "DASH", "CSX", "INTC",
        "WBD", "TTD", "CSGP", "CDW", "CHTR", "PYPL", "MRVL", "PDD", "SMCI", "AZN"
    )

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

        // Stop loss: giriş fiyatına göre maksimum kayıp yüzdesi (%2)
        const val STOP_LOSS_PCT = 0.02

        // Take profit: giriş fiyatına göre hedef kazanç yüzdesi (%4)
        const val TAKE_PROFIT_PCT = 0.04

        // Haber yoğunluğu filtresi: son N dakikada bu kadar haber varsa BUY engellenir
        const val NEWS_WINDOW_MINUTES = 60
        const val MAX_NEWS_COUNT = 3

        // Sayfalama: kaç sembol aynı anda izlensin
        const val PAGE_SIZE = 20

        // Her kaç ms'de bir sonraki sayfaya geç (2 dakika)
        const val PAGE_ROTATION_MS = 2 * 60 * 1000L
    }
}
