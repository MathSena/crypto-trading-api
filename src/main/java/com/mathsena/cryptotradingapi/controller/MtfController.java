package com.mathsena.cryptotradingapi.controller;

import com.mathsena.cryptotradingapi.model.MtfSignal;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.service.MtfStrategyService;
import com.mathsena.cryptotradingapi.service.MtfScannerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Controller REST para as Estratégias MTF (Multi-Time Frame).
 *
 * Endpoints:
 *  GET /api/v1/mtf/strategy  → Avalia Sniper Scalp + Trend Rider para um ativo
 *  GET /api/v1/mtf/scan      → Varre o mercado e retorna os melhores sinais MTF
 */
@RestController
@RequestMapping("/api/v1/mtf")
@Tag(name = "MTF Strategies API", description = "Estratégias Multi-Time Frame: Sniper Scalp 🎯 e Trend Rider 🚢")
@CrossOrigin(origins = "*")
public class MtfController {

  private final MtfStrategyService mtfStrategyService;
  private final MtfScannerService mtfScannerService;

  public MtfController(MtfStrategyService mtfStrategyService,
      MtfScannerService mtfScannerService) {
    this.mtfStrategyService = mtfStrategyService;
    this.mtfScannerService = mtfScannerService;
  }

  // ─── Estratégia MTF para um ativo ─────────────────────────────────────────

  @GetMapping("/strategy")
  @Operation(
      summary = "Avaliar estratégias MTF para um ativo",
      description = """
          Avalia as duas estratégias MTF (Multi-Time Frame) para um ativo:

          **🎯 Sniper Scalp** — O Caçador
          - Gráfico 1h: verifica que não está em queda forte (não apanhar faca a cair)
          - Gráfico 3m: RSI entre 30-43 (pânico) + MACD Bullish Cross
          - Risco: 15x alavancagem | SL ~0.8% | Meta: 5-10% ROI em 2-15 min

          **🚢 Trend Rider** — O Surfista
          - Gráfico 1h: RSI > 50 + MACD bullish + tendência de alta (exigência máxima)
          - Gráfico 3m: RSI entre 45-55 (pullback/respiro) + MACD Bullish Cross
          - Risco: 10x alavancagem | SL ~2.5% | Meta: 20-50%+ ROI em 1-6h

          **🛡️ Travas de Segurança:**
          - RSI 3m > 75 → bloqueio por exaustão
          - MACD 3m sem Bullish Cross → aguarda confirmação
          - Macro BEARISH → ignorar Trend Rider, focar em Sniper
          """
  )
  public ResponseEntity<Map<String, Object>> getStrategy(
      @Parameter(description = "Par de trading. Ex: BTCUSDT, ETHUSDT, SOLUSDT")
      @RequestParam String symbol,

      @Parameter(description = "Exchange: BINANCE ou BYBIT")
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange
  ) {
    try {
      MtfSignal signal = mtfStrategyService.evaluate(symbol.toUpperCase(), exchange);
      return ResponseEntity.ok(buildStrategyResponse(signal));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Scanner MTF ──────────────────────────────────────────────────────────

  @GetMapping("/scan")
  @Operation(
      summary = "Varrer mercado com estratégias MTF",
      description = """
          Varre os principais ativos do mercado e retorna aqueles com sinais MTF ativos.

          Retorna separado por estratégia:
          - **sniperScalp**: Ativos com Sniper Scalp ativo agora
          - **trendRider**: Ativos com Trend Rider ativo agora
          - **waiting**: Ativos com permissão concedida mas aguardando gatilho
          - **macroSentiment**: Sentimento geral do mercado baseado em 1h
          """
  )
  public ResponseEntity<Map<String, Object>> scanMtf(
      @Parameter(description = "Exchange: BINANCE ou BYBIT")
      @RequestParam(defaultValue = "BINANCE") TradingEnums.Exchange exchange,

      @Parameter(description = "Volume mínimo 24h em USDT (padrão: 50M)")
      @RequestParam(defaultValue = "50000000") double minVolumeUSDT,

      @Parameter(description = "Número máximo de ativos a analisar (padrão: 80)")
      @RequestParam(defaultValue = "80") int topSymbols
  ) {
    try {
      MtfScannerService.MtfScanResult result =
          mtfScannerService.scan(exchange, minVolumeUSDT, topSymbols);
      return ResponseEntity.ok(buildScanResponse(result));
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(buildError(e.getMessage()));
    }
  }

  // ─── Response Builders ────────────────────────────────────────────────────

  private Map<String, Object> buildStrategyResponse(MtfSignal s) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("timestamp", s.getGeneratedAt().toString());
    response.put("symbol", s.getSymbol());
    response.put("exchange", s.getExchange());

    // Estratégia
    Map<String, Object> strategy = new LinkedHashMap<>();
    strategy.put("type", s.getStrategyType().name());
    strategy.put("label", s.getStrategyLabel());
    strategy.put("status", s.getStatus().name());
    strategy.put("statusLabel", s.getStatus().getLabel());
    strategy.put("description", s.getStrategyDescription());
    response.put("strategy", strategy);

    // Avaliação Macro
    Map<String, Object> macro = new LinkedHashMap<>();
    macro.put("evaluation", s.getMacroEvaluation());
    macro.put("advice", s.getMacroAdvice());
    response.put("macro", macro);

    // Permissão do 1h
    Map<String, Object> permission = new LinkedHashMap<>();
    permission.put("granted", s.isPermissionGranted());
    permission.put("reason", s.getPermissionReason());
    response.put("permission1h", permission);

    // Gatilho do 3m
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("active", s.isTriggerActive());
    trigger.put("reason", s.getTriggerReason());
    response.put("trigger3m", trigger);

    // Travas de Segurança
    Map<String, Object> locks = new LinkedHashMap<>();
    locks.put("exhaustionBlock", s.isExhaustionBlock());
    locks.put("exhaustionDescription", s.isExhaustionBlock()
        ? "RSI 3m > 75 — mercado exausto, aguardar recuo" : "OK");
    locks.put("macdConfirmationRequired", s.isMacdConfirmationMissing());
    locks.put("macdDescription", s.isMacdConfirmationMissing()
        ? "MACD 3m ainda não cruzou para cima — aguardar gancho" : "OK");
    response.put("safetyLocks", locks);

    // Contexto 1h
    if (s.getContext1h() != null) {
      response.put("context1h", buildContextMap(s.getContext1h()));
    }

    // Contexto 3m
    if (s.getContext3m() != null) {
      response.put("context3m", buildContextMap(s.getContext3m()));
    }

    // Entrada e Risco (só se ACTIVE)
    if (s.getStatus() == TradingEnums.StrategyStatus.ACTIVE) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("price", s.getEntryPrice());
      entry.put("zoneLow", s.getEntryZoneLow());
      entry.put("zoneHigh", s.getEntryZoneHigh());
      response.put("entry", entry);

      Map<String, Object> sl = new LinkedHashMap<>();
      sl.put("price", s.getStopLoss());
      sl.put("percent", s.getStopLossPercent() + "%");
      sl.put("rationale", s.getStopLossRationale());
      response.put("stopLoss", sl);

      Map<String, Object> tp = new LinkedHashMap<>();
      tp.put("tp1", Map.of("price", s.getTakeProfit1(), "label", s.getTp1Label()));
      tp.put("tp2", Map.of("price", s.getTakeProfit2(), "label", s.getTp2Label()));
      tp.put("tp3", Map.of("price", s.getTakeProfit3(), "label", s.getTp3Label()));
      tp.put("riskRewardRatio", "1:" + s.getRiskRewardRatio());
      response.put("takeProfits", tp);

      Map<String, Object> risk = new LinkedHashMap<>();
      risk.put("suggestedLeverage", s.getSuggestedLeverage() + "x");
      risk.put("riskLevel", s.getRiskLevel() != null ? s.getRiskLevel().name() : "N/A");
      risk.put("riskLevelLabel", s.getRiskLevelLabel());
      response.put("riskManagement", risk);

      Map<String, Object> time = new LinkedHashMap<>();
      time.put("estimated", s.getEstimatedDuration());
      time.put("basis", s.getDurationBasis());
      response.put("timePrediction", time);
    } else {
      // Condições de espera
      if (s.getWaitingConditions() != null && !s.getWaitingConditions().isEmpty()) {
        response.put("waitingConditions", s.getWaitingConditions());
      }
    }

    // Alertas
    if (s.getAlerts() != null && !s.getAlerts().isEmpty()) {
      response.put("alerts", s.getAlerts());
    }

    return response;
  }

  private Map<String, Object> buildContextMap(MtfSignal.TimeframeContext ctx) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("interval", ctx.getInterval());
    map.put("currentPrice", ctx.getCurrentPrice());
    map.put("rsi", ctx.getRsi());
    map.put("rsiSignal", ctx.getRsiSignal());
    map.put("macdCrossover", ctx.getMacdCrossover());
    map.put("macdLine", ctx.getMacdLine());
    map.put("macdSignalLine", ctx.getMacdSignalLine());
    map.put("macdHistogram", ctx.getMacdHistogram());
    map.put("macdAboveZero", ctx.isMacdAboveZero());
    map.put("trend", ctx.getTrend().name());
    map.put("trendLabel", ctx.getTrendLabel());
    map.put("ema9", ctx.getEma9());
    map.put("ema21", ctx.getEma21());
    map.put("ema50", ctx.getEma50());
    map.put("emaTrend", ctx.getEmaTrend());
    map.put("volumeRatio", ctx.getVolumeRatio());
    map.put("highVolume", ctx.isHighVolume());
    return map;
  }

  private Map<String, Object> buildScanResponse(MtfScannerService.MtfScanResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("scannedAt", result.scannedAt().toString());
    response.put("exchange", result.exchange());
    response.put("totalScanned", result.totalScanned());
    response.put("totalAnalyzed", result.totalAnalyzed());
    response.put("scanDurationMs", result.scanDurationMs());

    // Sentimento macro
    Map<String, Object> sentiment = new LinkedHashMap<>();
    sentiment.put("evaluation", result.macroSentiment());
    sentiment.put("advice", result.macroAdvice());
    sentiment.put("bullishPercent", result.bullishPercent());
    sentiment.put("bearishPercent", result.bearishPercent());
    response.put("macroSentiment", sentiment);

    // Sinais Trend Rider ativos
    response.put("trendRiderActive", result.trendRiderActive().stream()
        .map(this::buildScanItemMap)
        .toList());

    // Sinais Sniper Scalp ativos
    response.put("sniperScalpActive", result.sniperScalpActive().stream()
        .map(this::buildScanItemMap)
        .toList());

    // Aguardando com permissão (quase prontos)
    response.put("waitingWithPermission", result.waitingWithPermission().stream()
        .map(this::buildScanItemMap)
        .toList());

    response.put("counts", Map.of(
        "trendRiderActive", result.trendRiderActive().size(),
        "sniperScalpActive", result.sniperScalpActive().size(),
        "waitingWithPermission", result.waitingWithPermission().size()
    ));

    return response;
  }

  private Map<String, Object> buildScanItemMap(MtfScannerService.MtfScanItem item) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("symbol", item.symbol());
    map.put("strategy", item.strategyType().name());
    map.put("strategyLabel", item.strategyType().getLabel());
    map.put("status", item.status().name());
    map.put("entryPrice", item.entryPrice());
    map.put("stopLoss", item.stopLoss());
    map.put("stopLossPercent", item.stopLossPercent() + "%");
    map.put("takeProfit1", item.tp1());
    map.put("takeProfit2", item.tp2());
    map.put("suggestedLeverage", item.leverage() + "x");
    map.put("rsi3m", item.rsi3m());
    map.put("rsi1h", item.rsi1h());
    map.put("macd3m", item.macd3m());
    map.put("trend1h", item.trend1h());
    map.put("statusReason", item.statusReason());
    return map;
  }

  private Map<String, Object> buildError(String message) {
    return Map.of("success", false, "error", message, "timestamp", Instant.now().toString());
  }
}
