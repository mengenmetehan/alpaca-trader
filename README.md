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

## Sembol Listesi ve Sayfalama

110 sembol (Dow Jones 30 + Nasdaq-100) 20'lik sayfalara bölünür. Her 2 dakikada bir sonraki sayfa aktif olur. Toplam tur süresi ~12 dakika. Engine cache'leri kalıcıdır — bir sembol ikinci kez geldiğinde sıfırdan ısınmaz.

Açık pozisyonlu semboller sayfa döndüğünde stream'den çıkarılmaz; her zaman aktif abonelikte tutulur.

## Strateji Mantığı

### Başlangıç
1. İlk sayfa (20 sembol) için Finnhub'dan F/K, PD/DD, Beta çek
2. Her sembol için 150 adet historical 1-dakikalık bar çek
3. ta4j indikatörlerini historical bar ile ısıt (minimum 40 bar gerekli)

### Gün İçi (Her 1-dk Bar) — Karar Sırası

Açık pozisyon varsa önce fiyat eşikleri kontrol edilir, sinyal beklenmez:

```
1. bar.low  ≤ giriş × (1 - STOP_LOSS_PCT)   → 🛑 STOP LOSS
2. bar.high ≥ giriş × (1 + TAKE_PROFIT_PCT) → 🎯 TAKE PROFIT
3. isSellSignal (teknik)                     → 🔴 SELL
```

Pozisyon yoksa BUY sinyali aranır:

**BUY koşulları (hepsi aynı anda):**
- RSI < 35 (aşırı satım)
- MACD histogram > 0 (momentum dönüşü)
- Fiyat Bollinger alt bandının altında
- Hacim, 20-bar ortalamasının 1.5x üzerinde
- F/K < 35 (temel filtre)

> Stop loss ve take profit `bar.low` / `bar.high` ile kontrol edilir. Bar kapanışı beklenmez — bar içinde eşik geçildiyse anında tetiklenir.

## Parametreler (Config.kt)

| Parametre | Varsayılan | Açıklama |
|---|---|---|
| RSI_PERIOD | 14 | RSI periyodu |
| RSI_OVERSOLD | 30 | Referans (isBuySignal 35 kullanır) |
| RSI_OVERBOUGHT | 70 | Referans (isSellSignal 65 kullanır) |
| MACD_FAST | 12 | MACD hızlı EMA |
| MACD_SLOW | 26 | MACD yavaş EMA |
| BOLLINGER_PERIOD | 20 | Bollinger SMA periyodu |
| VOLUME_MA_PERIOD | 20 | Hacim SMA periyodu |
| MAX_PE_RATIO | 35.0 | Maksimum F/K filtresi |
| ORDER_NOTIONAL | 1000.0 | Emir büyüklüğü (USD) |
| STOP_LOSS_PCT | 0.02 | Stop loss eşiği (%2) |
| TAKE_PROFIT_PCT | 0.04 | Take profit hedefi (%4) |
| PAGE_SIZE | 20 | Aynı anda izlenen sembol sayısı |
| PAGE_ROTATION_MS | 120000 | Sayfa değiştirme aralığı (2 dakika) |

## Genişletme Fikirleri

- [x] Stop-loss / take-profit seviyesi ekle
- [x] Tüm Dow Jones 30 + Nasdaq-100 sembollerini sayfalayarak tara
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
