# 📈 CryptoTrader API

API REST de análise de trading para futuros de criptomoedas. Busca dados em tempo real da **Binance** e **Bybit** (sem chave de API) e calcula indicadores técnicos para gerar sinais de compra/venda com Stop Loss e Take Profits automáticos.

---

## 🚀 Tecnologias

| Tecnologia | Versão | Uso |
|---|---|---|
| Java | 17 | Linguagem |
| Spring Boot | 3.5.0 | Framework |
| Maven | 3.8+ | Build & dependências |
| Caffeine | — | Cache em memória |
| Lombok | — | Redução de boilerplate |
| Jackson | — | Serialização JSON |
| SpringDoc OpenAPI | 2.8.4 | Documentação Swagger |

---

## ⚡ Quickstart

```bash
# 1. Clone e entre na pasta
git clone https://github.com/seuusuario/crypto-trading-api.git
cd crypto-trading-api

# 2. Build
mvn clean package -DskipTests

# 3. Inicie
java -jar target/crypto-trading-api-0.0.1-SNAPSHOT.jar
```

A API estará disponível em `http://localhost:8080`.

---

## 🗂️ Estrutura do Projeto

```
src/main/java/com/seuusuario/cryptotradingapi/
├── config/
│   ├── JacksonConfig.java          # ObjectMapper com suporte a datas Java 8+
│   ├── CacheConfig.java            # Caffeine: cache de 180s / 1000 entradas
│   ├── CorsConfig.java             # Libera localhost:5173 e localhost:3000
│   └── SwaggerConfig.java          # Metadados da documentação OpenAPI
│
├── controller/
│   ├── TradingController.java      # Endpoints de sinal, preço, candles e saúde
│   └── MarketScannerController.java# Endpoints do scanner de mercado
│
├── model/
│   ├── TradingEnums.java           # SignalType, TrendDirection, Exchange, etc.
│   ├── Candlestick.java            # OHLCV de uma vela
│   ├── IndicatorResult.java        # RSI, MACD, EMAs calculados
│   ├── TradingSignal.java          # Sinal completo com SL/TPs/alertas
│   ├── RankedAsset.java            # Ativo ranqueado pelo scanner
│   └── MarketScanResult.java       # Resultado do scan completo
│
└── service/
    ├── BinanceApiService.java      # Cliente HTTP para Binance Futures
    ├── BybitApiService.java        # Cliente HTTP para Bybit Linear
    ├── TechnicalIndicatorService.java # Cálculos: RSI, EMA, MACD
    ├── SignalGeneratorService.java  # Lógica de geração de sinal
    ├── RiskManagementService.java  # Cálculo de SL, TP e alavancagem
    ├── MarketDataService.java      # Orquestra dados de preço e candles
    ├── AssetScoringService.java    # Pontua ativos para o scanner
    ├── TradingService.java         # Orquestra o fluxo completo de sinal
    └── MarketScannerService.java   # Varre múltiplos ativos em paralelo
```

---

## 📡 Endpoints

### Trading

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/signal` | Sinal completo com SL, TP1/2/3 e alertas |
| `GET` | `/api/v1/indicators` | Indicadores técnicos (RSI, MACD, EMAs) |
| `GET` | `/api/v1/price` | Preço atual + dados 24h |
| `GET` | `/api/v1/candles` | Histórico de candles OHLCV |
| `GET` | `/api/v1/health` | Status da API |

### Scanner de Mercado

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/scanner/top` | Top 5 compra + Top 5 venda |
| `GET` | `/api/v1/scanner/top-buy` | Top ativos com sinal de compra |
| `GET` | `/api/v1/scanner/top-sell` | Top ativos com sinal de venda |
| `GET` | `/api/v1/scanner/sentiment` | Sentimento geral do mercado |

---

## 🔧 Parâmetros

### `GET /api/v1/signal`

| Parâmetro | Tipo | Obrigatório | Exemplo | Descrição |
|---|---|---|---|---|
| `symbol` | string | ✅ | `BTCUSDT` | Par de trading |
| `interval` | string | ✅ | `1h` | Timeframe |
| `exchange` | string | ✅ | `BINANCE` | `BINANCE` ou `BYBIT` |
| `balance` | number | ❌ | `1000` | Saldo em USDT (para dimensionar posição) |
| `leverage` | number | ❌ | `10` | Alavancagem desejada |

**Timeframes disponíveis:** `1m`, `5m`, `15m`, `30m`, `1h`, `4h`, `1d`

**Exemplo de requisição:**
```bash
curl "http://localhost:8080/api/v1/signal?symbol=BTCUSDT&interval=1h&exchange=BINANCE"
```

**Exemplo de resposta:**
```json
{
  "symbol": "BTCUSDT",
  "exchange": "BINANCE",
  "interval": "1h",
  "timestamp": "2024-01-15T14:30:00Z",
  "signalType": "BUY",
  "signalDescription": "Tendência de alta com RSI neutro e MACD positivo",
  "strengthPercent": "68%",
  "entryPrice": 42500.00,
  "stopLossPrice": 41650.00,
  "stopLossPercent": "-2.0%",
  "tp1Price": 43350.00,
  "tp2Price": 44200.00,
  "tp3Price": 45500.00,
  "riskRewardRatio": "1:2.5",
  "suggestedLeverage": "5x",
  "riskLevel": "MEDIUM",
  "estimatedDuration": "4-12h",
  "indicators": {
    "rsiValue": 54.2,
    "rsiSignal": "Neutro",
    "macdLine": 125.4,
    "trend": "UPTREND",
    "ema9": 42480.0,
    "ema21": 42100.0,
    "ema50": 41500.0
  },
  "alerts": [],
  "shouldExitNow": false
}
```

### `GET /api/v1/scanner/top`

| Parâmetro | Tipo | Padrão | Descrição |
|---|---|---|---|
| `exchange` | string | `BINANCE` | Exchange alvo |
| `interval` | string | `1h` | Timeframe da análise |
| `topSymbols` | number | `30` | Quantos ativos analisar |
| `minVolume` | number | — | Volume mínimo 24h em USDT |

---

## ⚙️ Configuração

Edite `src/main/resources/application.properties`:

```properties
# Porta do servidor
server.port=8080

# URLs das exchanges (endpoints públicos)
binance.api.base-url=https://fapi.binance.com
bybit.api.base-url=https://api.bybit.com

# Cache: entradas máximas e tempo de expiração
spring.cache.type=caffeine
spring.cache.caffeine.spec=maximumSize=1000,expireAfterWrite=180s

# Parâmetros de risco padrão
risk.default-stop-loss-percent=2.0
risk.max-risk-per-trade=1.0

# Swagger UI
springdoc.swagger-ui.path=/swagger-ui.html
```

---

## 📊 Indicadores Calculados

### RSI (Relative Strength Index)
- Período: **14**
- Suavização de **Wilder (RMA)**
- `> 70` → Sobrecomprado | `< 30` → Sobrevendido | entre → Neutro

### EMA (Exponential Moving Average)
- Períodos: **9**, **21** e **50**
- Fórmula: `EMA = (Preço − EMAanterior) × Multiplier + EMAanterior`
- `Multiplier = 2 / (período + 1)`

### MACD
- Linha MACD: `EMA12 − EMA26`
- Linha Signal: `EMA9 do MACD`
- Histograma: `MACD − Signal`
- Detecta cruzamentos bullish/bearish automaticamente

### Tendência (pelas EMAs)
| Condição | Tendência |
|---|---|
| EMA9 > EMA21 > EMA50 | `STRONG_UPTREND` |
| EMA9 > EMA21 | `UPTREND` |
| EMA9 ≈ EMA21 | `SIDEWAYS` |
| EMA9 < EMA21 | `DOWNTREND` |
| EMA9 < EMA21 < EMA50 | `STRONG_DOWNTREND` |

---

## 🛡️ Stop Loss e Take Profits

O Stop Loss é calculado combinando:
1. **Porcentagem mínima configurável** (padrão: 2%)
2. **EMA50 como suporte/resistência dinâmica**
    - LONG: SL abaixo da EMA50 se ela estiver abaixo do preço de entrada
    - SHORT: SL acima da EMA50 se ela estiver acima do preço de entrada

Os Take Profits são projeções baseadas no risco assumido (SL):
- **TP1**: `entrada ± (risco × 1.5)`
- **TP2**: `entrada ± (risco × 2.5)`
- **TP3**: `entrada ± (risco × 4.0)`

---

## 🗃️ Cache

Todas as chamadas às exchanges são cacheadas por **180 segundos** com o **Caffeine**. Isso evita rate limiting e melhora a latência em chamadas repetidas para o mesmo símbolo/intervalo.

| Cache | Chave | TTL |
|---|---|---|
| `binanceCandles` | `symbol + interval + limit` | 180s |
| `binancePrice` | `symbol` | 180s |
| `binanceTickers` | `"all"` | 180s |
| `bybitCandles` | `symbol + interval + limit` | 180s |
| `bybitPrice` | `symbol` | 180s |

---

## 📝 Documentação Interativa

Com a API rodando, acesse:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON:** `http://localhost:8080/api-docs`

---

## 🔗 Arquitetura Completa

Este backend é parte de um sistema de 3 camadas:

```
Browser (React :5173)
        │  /api/v1/*
        ▼
NestJS BFF (:3000)   ← proxy + serve o frontend em produção
        │  HTTP
        ▼
Java Spring Boot (:8080)   ← esta API
        │
        ├── Binance Futures API (público)
        └── Bybit Linear API   (público)
```

---

## ⚠️ Aviso

> Esta API é um projeto de estudo. Os sinais gerados são baseados em análise técnica e **não constituem conselho financeiro**. Use por sua conta e risco.

---

## 📄 Licença

MIT
