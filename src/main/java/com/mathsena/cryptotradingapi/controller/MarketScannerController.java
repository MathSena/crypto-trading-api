package com.mathsena.cryptotradingapi.controller;

import com.mathsena.cryptotradingapi.model.MarketScanResult;
import com.mathsena.cryptotradingapi.model.RankedAsset;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.service.MarketScannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller do Scanner de Mercado.
 *
 * Endpoints:
 *  GET /api/v1/scanner/top           → Top 5 Buy + Top 5 Sell (resultado completo)
 *  GET /api/v1/scanner/top-buy       → Apenas Top 5 para compra
 *  GET /api/v1/scanner/top-sell      → Apenas Top 5 para venda
 *  GET /api/v1/scanner/sentiment     → Sentimento geral do mercado
 */
@RestController
@RequestMapping("/api/v1/scanner")
@Tag(name = "Market Scanner", description = "Scanner automático dos melhores ativos para trading")
@CrossOrigin(origins = "*")
public class MarketScannerController {

  private final MarketScannerService scannerService;

  public MarketScannerController(MarketScannerService scannerService) {
    this.scannerService = scannerService;
  }

  // ─── Scan Completo ────────────────────────────────────────────────────────

  @GetMapping("/top")
  @Operation(
      summary = "🔍 Top 5 Buy + Top 5 Sell",
      description = """
            Escaneia automaticamente o mercado de futuros USDT da exchange,
            analisa os ativos mais líquidos com RSI + MACD + EMA + Volume
            e retorna os 5 melhores para COMPRA e os 5 melhores para VENDA.
            
            **Processo:**
            1. Busca lista de ativos da API pública da Binance/Bybit (sem API key)
            2. Filtra por volume mínimo 24h (padrão: 50M USDT)
            3. Analisa os top N mais líquidos em paralelo
            4. Calcula score composto: MACD(30%) + RSI(25%) + Tendência(25%) + Volume(10%) + Momentum(10%)
            5. Ranqueia e retorna top 5 buy e top 5 sell
            
            **⚠️ Aviso:** Este endpoint pode levar 30-120 segundos dependendo do número de ativos.
            Use `topSymbols=30` para testes rápidos.
            """
  )
  public ResponseEntity<Map<String, Object>> scanTop(
      @Parameter(description = "Exchange: BINANCE ou BYBIT")
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,

      @Parameter(description = "Timeframe para análise: 1h, 4h, 15m, 1d")
      @RequestParam(defaultValue = "1h") String interval,

      @Parameter(description = "Volume mínimo 24h em USDT (0 = padrão 50M). Ex: 50000000")
      @RequestParam(defaultValue = "0") double minVolumeUSDT,

      @Parameter(description = "Quantos ativos mais líquidos analisar (0 = padrão 80, use 30 para teste rápido)")
      @RequestParam(defaultValue = "0") int topSymbols
  ) {
    try {
      MarketScanResult result = scannerService.scan(exchange, interval, minVolumeUSDT, topSymbols);
      return ResponseEntity.ok(buildFullScanResponse(result));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Top 5 Buy ────────────────────────────────────────────────────────────

  @GetMapping("/top-buy")
  @Operation(
      summary = "🟢 Top 5 melhores para COMPRA",
      description = "Retorna apenas os 5 melhores ativos para posição LONG (compra)."
  )
  public ResponseEntity<Map<String, Object>> topBuy(
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,
      @RequestParam(defaultValue = "1h") String interval,
      @RequestParam(defaultValue = "0") double minVolumeUSDT,
      @RequestParam(defaultValue = "0") int topSymbols
  ) {
    try {
      MarketScanResult result = scannerService.scan(exchange, interval, minVolumeUSDT, topSymbols);
      return ResponseEntity.ok(buildBuyScanResponse(result));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Top 5 Sell ───────────────────────────────────────────────────────────

  @GetMapping("/top-sell")
  @Operation(
      summary = "🔴 Top 5 melhores para VENDA",
      description = "Retorna apenas os 5 melhores ativos para posição SHORT (venda)."
  )
  public ResponseEntity<Map<String, Object>> topSell(
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,
      @RequestParam(defaultValue = "1h") String interval,
      @RequestParam(defaultValue = "0") double minVolumeUSDT,
      @RequestParam(defaultValue = "0") int topSymbols
  ) {
    try {
      MarketScanResult result = scannerService.scan(exchange, interval, minVolumeUSDT, topSymbols);
      return ResponseEntity.ok(buildSellScanResponse(result));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Sentimento de Mercado ────────────────────────────────────────────────

  @GetMapping("/sentiment")
  @Operation(
      summary = "📊 Sentimento geral do mercado",
      description = "Retorna o sentimento macro do mercado baseado na distribuição de sinais."
  )
  public ResponseEntity<Map<String, Object>> sentiment(
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,
      @RequestParam(defaultValue = "1h") String interval,
      @RequestParam(defaultValue = "0") double minVolumeUSDT,
      @RequestParam(defaultValue = "0") int topSymbols
  ) {
    try {
      MarketScanResult result = scannerService.scan(exchange, interval, minVolumeUSDT, topSymbols);
      return ResponseEntity.ok(buildSentimentResponse(result));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Response Builders ────────────────────────────────────────────────────

  private Map<String, Object> buildFullScanResponse(MarketScanResult r) {
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("success", true);
    res.put("scannedAt", r.getScannedAt().toString());
    res.put("exchange", r.getExchange());
    res.put("interval", r.getInterval());
    res.put("scanDurationSeconds", r.getScanDurationMs() / 1000.0);

    // Resumo do scan
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalScanned", r.getTotalSymbolsScanned());
    summary.put("totalAnalyzed", r.getTotalAnalyzed());
    summary.put("buySignals", r.getTotalBuySignals());
    summary.put("sellSignals", r.getTotalSellSignals());
    summary.put("neutral", r.getTotalNeutral());
    summary.put("minVolume24hUSDT", r.getMinVolume24hUSDT());
    res.put("scanSummary", summary);

    // Sentimento
    Map<String, Object> sentiment = new LinkedHashMap<>();
    sentiment.put("sentiment", r.getMarketSentiment());
    sentiment.put("sentimentLabel", r.getMarketSentimentLabel());
    sentiment.put("bullishPercent", r.getDominanceBullPercent() + "%");
    sentiment.put("bearishPercent", r.getDominanceBearPercent() + "%");
    res.put("marketSentiment", sentiment);

    // Metodologia
    res.put("scoringMethod", Map.of(
        "macd",     "30% do score",
        "rsi",      "25% do score",
        "trend",    "25% do score",
        "volume",   "10% do score",
        "momentum", "10% do score"
    ));

    // Rankings
    res.put("top5Buy",  r.getTop5Buy().stream().map(this::buildAssetMap).collect(Collectors.toList()));
    res.put("top5Sell", r.getTop5Sell().stream().map(this::buildAssetMap).collect(Collectors.toList()));

    res.put("timestamp", Instant.now().toString());
    return res;
  }

  private Map<String, Object> buildBuyScanResponse(MarketScanResult r) {
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("success", true);
    res.put("type", "TOP_5_BUY");
    res.put("exchange", r.getExchange());
    res.put("interval", r.getInterval());
    res.put("scannedAt", r.getScannedAt().toString());
    res.put("totalAnalyzed", r.getTotalAnalyzed());
    res.put("marketSentiment", r.getMarketSentimentLabel());
    res.put("top5Buy", r.getTop5Buy().stream().map(this::buildAssetMap).collect(Collectors.toList()));
    res.put("timestamp", Instant.now().toString());
    return res;
  }

  private Map<String, Object> buildSellScanResponse(MarketScanResult r) {
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("success", true);
    res.put("type", "TOP_5_SELL");
    res.put("exchange", r.getExchange());
    res.put("interval", r.getInterval());
    res.put("scannedAt", r.getScannedAt().toString());
    res.put("totalAnalyzed", r.getTotalAnalyzed());
    res.put("marketSentiment", r.getMarketSentimentLabel());
    res.put("top5Sell", r.getTop5Sell().stream().map(this::buildAssetMap).collect(Collectors.toList()));
    res.put("timestamp", Instant.now().toString());
    return res;
  }

  private Map<String, Object> buildSentimentResponse(MarketScanResult r) {
    Map<String, Object> res = new LinkedHashMap<>();
    res.put("success", true);
    res.put("exchange", r.getExchange());
    res.put("interval", r.getInterval());
    res.put("scannedAt", r.getScannedAt().toString());
    res.put("marketSentiment", r.getMarketSentiment());
    res.put("marketSentimentLabel", r.getMarketSentimentLabel());
    res.put("bullishPercent", r.getDominanceBullPercent());
    res.put("bearishPercent", r.getDominanceBearPercent());
    res.put("neutralPercent", Math.round((100 - r.getDominanceBullPercent() - r.getDominanceBearPercent()) * 10.0) / 10.0);
    res.put("totalBuySignals", r.getTotalBuySignals());
    res.put("totalSellSignals", r.getTotalSellSignals());
    res.put("totalNeutral", r.getTotalNeutral());
    res.put("totalAnalyzed", r.getTotalAnalyzed());

    // Top buys e sells resumidos
    res.put("leadingBuys", r.getTop5Buy().stream()
        .map(a -> Map.of("symbol", a.getSymbol(), "score", a.getScore(), "grade", a.getScoreGrade()))
        .collect(Collectors.toList()));
    res.put("leadingSells", r.getTop5Sell().stream()
        .map(a -> Map.of("symbol", a.getSymbol(), "score", a.getScore(), "grade", a.getScoreGrade()))
        .collect(Collectors.toList()));
    res.put("timestamp", Instant.now().toString());
    return res;
  }

  private Map<String, Object> buildAssetMap(RankedAsset a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rank", a.getRank());
    m.put("symbol", a.getSymbol());
    m.put("signal", a.getSignalLabel());
    m.put("score", a.getScore());
    m.put("grade", a.getScoreGrade());
    m.put("signalStrength", Math.round(a.getSignalStrength() * 100) + "%");
    m.put("rankingReason", a.getRankingReason());

    // Preço e mercado
    Map<String, Object> mkt = new LinkedHashMap<>();
    mkt.put("currentPrice", a.getCurrentPrice());
    mkt.put("change24h", a.getPriceChangePercent24h() + "%");
    mkt.put("volume24hUSDT", formatVolume(a.getVolume24hUSDT()));
    mkt.put("volumeRatio", a.getVolumeRatio() + "x");
    m.put("market", mkt);

    // Indicadores
    Map<String, Object> ind = new LinkedHashMap<>();
    ind.put("rsi", Map.of("value", a.getRsi(), "signal", a.getRsiSignal()));
    ind.put("macd", Map.of("histogram", a.getMacdHistogram(), "crossover", a.getMacdCrossover()));
    ind.put("trend", Map.of("type", a.getTrend().name(), "label", a.getTrend().getLabel()));
    m.put("indicators", ind);

    // Trade setup
    Map<String, Object> trade = new LinkedHashMap<>();
    trade.put("entry", a.getEntryPrice());
    trade.put("stopLoss", Map.of("price", a.getStopLoss(), "percent", a.getStopLossPercent() + "%"));
    trade.put("takeProfit1", Map.of("price", a.getTakeProfit1(), "label", "R:R 1:1 (conservador)"));
    trade.put("takeProfit2", Map.of("price", a.getTakeProfit2(), "label", "R:R 1:2 (principal)"));
    trade.put("takeProfit3", Map.of("price", a.getTakeProfit3(), "label", "R:R 1:3 (agressivo)"));
    trade.put("riskReward", "1:" + a.getRiskRewardRatio());
    trade.put("leverage", a.getSuggestedLeverage() + "x");
    trade.put("riskLevel", a.getRiskLevel().getLabel());
    trade.put("estimatedDuration", a.getEstimatedDuration());
    m.put("tradeSetup", trade);

    // Scores individuais (transparência)
    Map<String, Object> scores = new LinkedHashMap<>();
    scores.put("macd",     a.getMacdScore()     + "/100 (peso 30%)");
    scores.put("rsi",      a.getRsiScore()      + "/100 (peso 25%)");
    scores.put("trend",    a.getTrendScore()    + "/100 (peso 25%)");
    scores.put("volume",   a.getVolumeScore()   + "/100 (peso 10%)");
    scores.put("momentum", a.getMomentumScore() + "/100 (peso 10%)");
    scores.put("total",    a.getScore() + "/100");
    m.put("scoreBreakdown", scores);

    // Alertas
    if (a.getTopAlerts() != null && !a.getTopAlerts().isEmpty()) {
      m.put("alerts", a.getTopAlerts());
    }

    return m;
  }

  private String formatVolume(double volume) {
    if (volume >= 1_000_000_000) return String.format("$%.2fB", volume / 1_000_000_000);
    if (volume >= 1_000_000)     return String.format("$%.1fM", volume / 1_000_000);
    return String.format("$%.0f", volume);
  }

  private Map<String, Object> buildError(String message) {
    Map<String, Object> res = new HashMap<>();
    res.put("success", false);
    res.put("error", message);
    res.put("timestamp", Instant.now().toString());
    return res;
  }
}