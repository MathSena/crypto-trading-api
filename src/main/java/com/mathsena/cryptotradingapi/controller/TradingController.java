package com.mathsena.cryptotradingapi.controller;

import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.model.TradingSignal;
import com.mathsena.cryptotradingapi.service.TradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Controller principal da API REST de trading de cripto.
 *
 * Endpoints disponíveis:
 *  GET /api/v1/signal            → Sinal completo com SL/TP/alertas
 *  GET /api/v1/indicators        → Indicadores técnicos brutos
 *  GET /api/v1/price             → Preço atual
 *  GET /api/v1/candles           → Candles OHLCV
 *  GET /api/v1/health            → Status da API
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Trading Signals API", description = "Sinais de trading para futuros de cripto")
@CrossOrigin(origins = "*")
public class TradingController {

  private final TradingService tradingService;

  public TradingController(TradingService tradingService) {
    this.tradingService = tradingService;
  }

  // ─── Sinal Completo ───────────────────────────────────────────────────────

  @GetMapping("/signal")
  @Operation(
      summary = "Gerar sinal de trading",
      description = """
            Gera um sinal completo de compra ou venda para um par de futuros de cripto.
            
            Inclui:
            - Tipo de sinal (Compra Forte / Compra / Neutro / Venda / Venda Forte)
            - Confiança do sinal (0% a 100%)
            - Stop Loss com justificativa técnica
            - 3 alvos de Take Profit (conservador, principal, agressivo)
            - Risk:Reward ratio
            - Alavancagem recomendada
            - Alertas e avisos de saída
            - Previsão de duração da trade
            - Indicadores: RSI, MACD, EMA (9, 21, 50), Volume
            """
  )
  public ResponseEntity<Map<String, Object>> getSignal(
      @Parameter(description = "Par de trading. Ex: BTCUSDT, ETHUSDT, SOLUSDT")
      @RequestParam String symbol,

      @Parameter(description = "Timeframe. Opções: 1m, 5m, 15m, 30m, 1h, 4h, 1d")
      @RequestParam(defaultValue = "1h") String interval,

      @Parameter(description = "Exchange: BINANCE ou BYBIT")
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,

      @Parameter(description = "Saldo da conta em USDT (opcional, para cálculo de posição)")
      @RequestParam(defaultValue = "0") double accountBalance,

      @Parameter(description = "% de risco por trade (ex: 1.0 = 1%)")
      @RequestParam(defaultValue = "1.0") double riskPercent
  ) {
    try {
      TradingSignal signal = tradingService.getSignal(
          symbol.toUpperCase(), interval, exchange, accountBalance, riskPercent);

      return ResponseEntity.ok(buildSignalResponse(signal));

    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildErrorResponse(e.getMessage()));
    }
  }

  // ─── Apenas Indicadores ───────────────────────────────────────────────────

  @GetMapping("/indicators")
  @Operation(
      summary = "Obter indicadores técnicos",
      description = "Retorna RSI, MACD, EMAs e análise de volume sem gerar sinal completo."
  )
  public ResponseEntity<Map<String, Object>> getIndicators(
      @RequestParam String symbol,
      @RequestParam(defaultValue = "1h") String interval,
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange
  ) {
    try {
      IndicatorResult indicators = tradingService.getIndicators(
          symbol.toUpperCase(), interval, exchange);

      return ResponseEntity.ok(buildIndicatorsResponse(indicators));

    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildErrorResponse(e.getMessage()));
    }
  }

  // ─── Preço Atual ─────────────────────────────────────────────────────────

  @GetMapping("/price")
  @Operation(summary = "Preço atual (Mark Price)", description = "Retorna o preço atual de mercado (mark price) do ativo.")
  public ResponseEntity<Map<String, Object>> getPrice(
      @RequestParam String symbol,
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange
  ) {
    try {
      double price = tradingService.getCurrentPrice(symbol.toUpperCase(), exchange);

      Map<String, Object> response = new HashMap<>();
      response.put("success", true);
      response.put("symbol", symbol.toUpperCase());
      response.put("exchange", exchange.name());
      response.put("price", price);
      response.put("timestamp", Instant.now().toString());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildErrorResponse(e.getMessage()));
    }
  }

  // ─── Candles OHLCV ───────────────────────────────────────────────────────

  @GetMapping("/candles")
  @Operation(summary = "Candles OHLCV", description = "Retorna candles históricos do ativo.")
  public ResponseEntity<Map<String, Object>> getCandles(
      @RequestParam String symbol,
      @RequestParam(defaultValue = "1h") String interval,
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,
      @RequestParam(defaultValue = "50") int limit
  ) {
    try {
      var candles = tradingService.getCandles(symbol.toUpperCase(), interval, exchange, limit);

      Map<String, Object> response = new HashMap<>();
      response.put("success", true);
      response.put("symbol", symbol.toUpperCase());
      response.put("interval", interval);
      response.put("exchange", exchange.name());
      response.put("count", candles.size());
      response.put("candles", candles);
      response.put("timestamp", Instant.now().toString());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildErrorResponse(e.getMessage()));
    }
  }

  // ─── Health Check ─────────────────────────────────────────────────────────

  @GetMapping("/health")
  @Operation(summary = "Status da API")
  public ResponseEntity<Map<String, Object>> health() {
    Map<String, Object> response = new HashMap<>();
    response.put("status", "UP");
    response.put("service", "Crypto Trading Signal API");
    response.put("version", "1.0.0");
    response.put("timestamp", Instant.now().toString());
    response.put("endpoints", Map.of(
        "signal", "GET /api/v1/signal?symbol=BTCUSDT&interval=1h&exchange=BINANCE",
        "indicators", "GET /api/v1/indicators?symbol=BTCUSDT&interval=1h",
        "price", "GET /api/v1/price?symbol=BTCUSDT",
        "candles", "GET /api/v1/candles?symbol=BTCUSDT&interval=1h&limit=50",
        "swagger", "http://localhost:8080/swagger-ui.html"
    ));
    return ResponseEntity.ok(response);
  }

  // ─── Response Builders ────────────────────────────────────────────────────

  private Map<String, Object> buildSignalResponse(TradingSignal s) {
    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("timestamp", s.getGeneratedAt().toString());
    response.put("symbol", s.getSymbol());
    response.put("exchange", s.getExchange());
    response.put("interval", s.getInterval());

    // Sinal principal
    Map<String, Object> signal = new HashMap<>();
    signal.put("type", s.getSignalType().name());
    signal.put("label", s.getSignalLabel());
    signal.put("strength", s.getSignalStrength());
    signal.put("strengthPercent", Math.round(s.getSignalStrength() * 100) + "%");
    signal.put("description", s.getSignalDescription());
    response.put("signal", signal);

    // Entrada
    Map<String, Object> entry = new HashMap<>();
    entry.put("price", s.getEntryPrice());
    entry.put("zoneLow", s.getEntryZoneLow());
    entry.put("zoneHigh", s.getEntryZoneHigh());
    response.put("entry", entry);

    // Stop Loss
    Map<String, Object> sl = new HashMap<>();
    sl.put("price", s.getStopLoss());
    sl.put("percent", s.getStopLossPercent() + "%");
    sl.put("rationale", s.getStopLossRationale());
    response.put("stopLoss", sl);

    // Take Profits
    Map<String, Object> tp = new HashMap<>();
    tp.put("tp1", Map.of("price", s.getTakeProfit1(), "label", "Conservador (R:R 1:1)"));
    tp.put("tp2", Map.of("price", s.getTakeProfit2(), "label", "Principal (R:R 1:2)"));
    tp.put("tp3", Map.of("price", s.getTakeProfit3(), "label", "Agressivo (R:R 1:3)"));
    tp.put("riskRewardRatio", "1:" + s.getRiskRewardRatio());
    response.put("takeProfits", tp);

    // Gestão de risco
    Map<String, Object> risk = new HashMap<>();
    risk.put("level", s.getRiskLevel().name());
    risk.put("levelLabel", s.getRiskLevel().getLabel());
    risk.put("suggestedLeverage", s.getSuggestedLeverage() + "x");
    risk.put("positionSizePercent", s.getPositionSizePercent() + "%");
    response.put("riskManagement", risk);

    // Alertas
    Map<String, Object> alerts = new HashMap<>();
    alerts.put("general", s.getAlerts());
    alerts.put("exitWarnings", s.getExitWarnings());
    alerts.put("targetAdjustments", s.getTargetAdjustments());
    alerts.put("shouldExitNow", s.isShouldExit());
    if (s.getExitReason() != null) alerts.put("exitReason", s.getExitReason());
    response.put("alerts", alerts);

    // Previsão de tempo
    Map<String, Object> timePrediction = new HashMap<>();
    timePrediction.put("estimated", s.getEstimatedDuration());
    timePrediction.put("min", s.getEstimatedDurationMin());
    timePrediction.put("max", s.getEstimatedDurationMax());
    timePrediction.put("basis", s.getTimePredictionBasis());
    response.put("timePrediction", timePrediction);

    // Tendência e Indicadores
    Map<String, Object> market = new HashMap<>();
    market.put("trend", s.getTrend().name());
    market.put("trendLabel", s.getTrend().getLabel());
    response.put("marketContext", market);

    // Indicadores técnicos detalhados
    if (s.getIndicators() != null) {
      IndicatorResult ind = s.getIndicators();
      Map<String, Object> indicators = new HashMap<>();

      indicators.put("rsi", Map.of(
          "value", Math.round(ind.getRsi() * 100.0) / 100.0,
          "signal", ind.getRsiSignal()
      ));

      indicators.put("macd", Map.of(
          "line", Math.round(ind.getMacdLine() * 10000.0) / 10000.0,
          "signalLine", Math.round(ind.getMacdSignalLine() * 10000.0) / 10000.0,
          "histogram", Math.round(ind.getMacdHistogram() * 10000.0) / 10000.0,
          "crossover", ind.getMacdCrossover(),
          "aboveZero", ind.isMacdAboveZero()
      ));

      indicators.put("ema", Map.of(
          "ema9", Math.round(ind.getEma9() * 100.0) / 100.0,
          "ema21", Math.round(ind.getEma21() * 100.0) / 100.0,
          "ema50", Math.round(ind.getEma50() * 100.0) / 100.0,
          "trend", ind.getEmaTrend()
      ));

      indicators.put("volume", Map.of(
          "current", Math.round(ind.getCurrentVolume()),
          "average", Math.round(ind.getAverageVolume()),
          "ratio", Math.round(ind.getVolumeRatio() * 100.0) / 100.0,
          "isHigh", ind.isHighVolume()
      ));

      indicators.put("price", Map.of(
          "current", ind.getCurrentPrice(),
          "change24h", Math.round(ind.getPriceChange24h() * 100.0) / 100.0,
          "changePercent24h", Math.round(ind.getPriceChangePercent24h() * 100.0) / 100.0 + "%"
      ));

      response.put("indicators", indicators);
    }

    return response;
  }

  private Map<String, Object> buildIndicatorsResponse(IndicatorResult ind) {
    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("symbol", ind.getSymbol());
    response.put("interval", ind.getInterval());
    response.put("currentPrice", ind.getCurrentPrice());
    response.put("trend", Map.of("type", ind.getTrend().name(), "label", ind.getTrend().getLabel()));
    response.put("bullishScore", ind.getBullishScore());
    response.put("bearishScore", ind.getBearishScore());
    response.put("rsi", Map.of("value", ind.getRsi(), "signal", ind.getRsiSignal()));
    response.put("macd", Map.of(
        "line", ind.getMacdLine(),
        "signalLine", ind.getMacdSignalLine(),
        "histogram", ind.getMacdHistogram(),
        "crossover", ind.getMacdCrossover()
    ));
    response.put("emas", Map.of(
        "ema9", ind.getEma9(),
        "ema21", ind.getEma21(),
        "ema50", ind.getEma50(),
        "trend", ind.getEmaTrend()
    ));
    response.put("volume", Map.of(
        "current", ind.getCurrentVolume(),
        "average", ind.getAverageVolume(),
        "ratio", ind.getVolumeRatio(),
        "isHigh", ind.isHighVolume()
    ));
    response.put("timestamp", Instant.now().toString());
    return response;
  }

  private Map<String, Object> buildErrorResponse(String message) {
    Map<String, Object> response = new HashMap<>();
    response.put("success", false);
    response.put("error", message);
    response.put("timestamp", Instant.now().toString());
    return response;
  }
}