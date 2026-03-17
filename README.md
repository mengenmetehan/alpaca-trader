# AlgoTrader — Kotlin + Alpaca + Finnhub

NASDAQ/Dow Jones hisselerinde temel + teknik analiz tabanlı paper trading botu.

## Mimari

```
Finnhub REST          → Sabah F/K, PD/DD filtresi
Alpaca REST           → Historical bar (indikatör ısınması)
Alpaca WebSocket      → Realtime 1-dakikalık bar stream
ta4j                  → RSI, MACD, Bollinger, Hacim hesabı
TradingStrategy       → Sinyal birleştirici + karar motoru
Alpaca Paper Trading  → Komisyonsuz simülasyon emri
```

## Kurulum

### 1. API Key'ler

**Alpaca (ücretsiz paper trading):**
- https://app.alpaca.markets adresine git
- "Paper Trading" → "API Keys" → yeni key oluştur

**Finnhub (ücretsiz fundamental data):**
- https://finnhub.io/register
- Dashboard → API Key kopyala

### 2. Environment variables

```bash
export ALPACA_KEY_ID="PKXXXXXXXXXXXXXXXX"
export ALPACA_SECRET_KEY="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
export FINNHUB_API_KEY="xxxxxxxxxxxxxxxxxxxxxxxxxx"
```

### 3. Çalıştır

```bash
./gradlew run
```

## Strateji Mantığı

### Sabah Başlangıcı
1. Finnhub → WATCHLIST için F/K, PD/DD, Beta çek
2. Alpaca REST → Her sembol için 150 adet historical 1-dakikalık bar çek
3. ta4j indikatörlerini historical bar ile ısıt (MACD_SLOW + MACD_SIGNAL bar gerekli)

### Gün İçi (Her 1-dk Bar)

**BUY sinyali (hepsi aynı anda):**
- RSI < 35 (aşırı satım)
- MACD histogram > 0 (momentum dönüşü)
- Fiyat Bollinger alt bandının altında
- Hacim, 20-bar ortalamasının 1.5x üzerinde
- F/K < 35 (temel filtre)

**SELL sinyali:**
- RSI > 65 (aşırı alım)
- MACD histogram < 0
- Fiyat Bollinger üst bandının üzerinde
- Hacim 1.5x üzerinde

## Parametreler (Config.kt)

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| RSI_PERIOD | 14 | RSI periyodu |
| RSI_OVERSOLD | 30 | Aşırı satım eşiği |
| RSI_OVERBOUGHT | 70 | Aşırı alım eşiği |
| MACD_FAST | 12 | MACD hızlı EMA |
| MACD_SLOW | 26 | MACD yavaş EMA |
| BOLLINGER_PERIOD | 20 | Bollinger SMA periyodu |
| VOLUME_MA_PERIOD | 20 | Hacim SMA periyodu |
| MAX_PE_RATIO | 35.0 | Maksimum F/K filtresi |
| ORDER_NOTIONAL | 1000.0 | Emir büyüklüğü (USD) |

## Genişletme Fikirleri

- [ ] Stop-loss / take-profit seviyesi ekle
- [ ] KAP benzeri SEC/EDGAR haber filtresi (Finnhub news API)
- [ ] Birden fazla strateji (momentum, mean-reversion) aynı anda
- [ ] PostgreSQL ile sinyal ve emir geçmişi kaydet
- [ ] Telegram/Slack bildirim entegrasyonu
- [ ] Backtesting modülü (Alpaca historical data + aynı strateji)

## Bağımlılıklar

- `kotlinx-coroutines` — async WebSocket stream
- `okhttp3` — HTTP/WebSocket client
- `kotlinx-serialization` — JSON parse
- `ta4j` — RSI, MACD, Bollinger, SMA
- `kotlin-logging` + `logback` — structured logging
