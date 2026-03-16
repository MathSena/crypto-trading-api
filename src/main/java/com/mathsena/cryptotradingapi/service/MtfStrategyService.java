package com.mathsena.cryptotradingapi.service;

import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.MtfSignal;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Serviço das Estratégias MTF (Multi-Time Frame).
 *
 * Implementa duas estratégias governadas pela técnica MTF:
 *   - Gráfico 1h: "O Chefe" — define a DIREÇÃO e concede PERMISSÃO
 *   - Gráfico 3m: "O Gatilho" — define o MOMENTO EXATO da entrada
 *
 * ─────────────────────────────────────────────────────────────────
 * 🎯 SNIPER SCALP ("O Caçador")
 *   Objetivo: Lucro rápido (5-10% ROI) em 2 a 15 minutos
 *   Gatilho 3m: RSI entre 30-43 + MACD Bullish Cross
 *   Permissão 1h: tendência NÃO é STRONG_DOWNTREND
 *   Risco: 15x alavancagem, SL ~0.8%
 *
 * 🚢 TREND RIDER ("O Surfista")
 *   Objetivo: Capturar grandes movimentos (20-50%+ ROI) em 1-6 horas
 *   Permissão 1h: RSI > 50 + MACD acima de zero + tendência de alta
 *   Gatilho 3m: RSI entre 45-55 (pullback/respiro) + MACD Bullish Cross
 *   Risco: 10x alavancagem, SL ~2.5%
 *
 * ─────────────────────────────────────────────────────────────────
 * 🛡️ TRAVAS DE SEGURANÇA (modo AGUARDANDO)
 *   1. Exaustão: RSI 3m > 75 → bloqueia compra
 *   2. Obrigação do Cruzamento: MACD 3m deve ter BULLISH_CROSS
 *   3. Avaliação Macro: 1h BEARISH → ignorar Trend Rider
 */
@Service
public class MtfStrategyService {

  private static final Logger log = LoggerFactory.getLogger(MtfStrategyService.class);

  // ── Configurações Sniper Scalp ─────────────────────────────────────────────
  private static final double SNIPER_RSI_MIN      = 30.0;
  private static final double SNIPER_RSI_MAX      = 43.0;
  private static final double SNIPER_LEVERAGE     = 15.0;
  private static final double SNIPER_SL_PERCENT   = 0.8;

  // ── Configurações Trend Rider ──────────────────────────────────────────────
  private static final double RIDER_RSI_MIN       = 45.0;
  private static final double RIDER_RSI_MAX       = 55.0;
  private static final double RIDER_RSI_1H_MIN    = 50.0;
  private static final double RIDER_LEVERAGE      = 10.0;
  private static final double RIDER_SL_PERCENT    = 2.5;

  // ── Trava de Exaustão (aplicada a ambas) ──────────────────────────────────
  private static final double EXHAUSTION_RSI      = 75.0;

  private final BinanceApiService binanceApiService;
  private final BybitApiService bybitApiService;
  private final TechnicalIndicatorService indicatorService;
  private final RiskManagementService riskService;

  public MtfStrategyService(BinanceApiService binanceApiService,
      BybitApiService bybitApiService,
      TechnicalIndicatorService indicatorService,
      RiskManagementService riskService) {
    this.binanceApiService = binanceApiService;
    this.bybitApiService = bybitApiService;
    this.indicatorService = indicatorService;
    this.riskService = riskService;
  }

  // ─── Endpoint Principal ────────────────────────────────────────────────────

  /**
   * Avalia as duas estratégias MTF para um ativo e retorna o melhor sinal.
   * Prioridade: Trend Rider > Sniper Scalp > AGUARDANDO.
   */
  @Cacheable(value = "mtfSignals", key = "#symbol + '-' + #exchange")
  public MtfSignal evaluate(String symbol, TradingEnums.Exchange exchange) {
    log.info("Avaliando estratégias MTF: {} | {}", symbol, exchange);

    // 1. Buscar candles dos dois timeframes em paralelo
    List<Candlestick> candles1h = fetchCandles(symbol, "1h", exchange, 100);
    List<Candlestick> candles3m = fetchCandles(symbol, "3m", exchange, 100);

    // 2. Calcular indicadores para cada timeframe
    IndicatorResult ind1h = indicatorService.calculate(candles1h);
    IndicatorResult ind3m = indicatorService.calculate(candles3m);

    // 3. Construir contextos
    MtfSignal.TimeframeContext ctx1h = buildContext("1h", ind1h);
    MtfSignal.TimeframeContext ctx3m = buildContext("3m", ind3m);

    // 4. Avaliação macro (sentimento do 1h)
    String macroEval  = evaluateMacro(ind1h);
    String macroAdvice = buildMacroAdvice(macroEval);

    // 5. Travas de segurança globais
    boolean exhaustionBlock = ind3m.getRsi() > EXHAUSTION_RSI;

    log.debug("{} | 1h RSI={} Trend={} | 3m RSI={} MACD={} | Exhaustion={}",
        symbol,
        String.format("%.1f", ind1h.getRsi()), ind1h.getTrend(),
        String.format("%.1f", ind3m.getRsi()), ind3m.getMacdCrossover(),
        exhaustionBlock);

    // 6. Tentar Trend Rider primeiro (estratégia de maior qualidade)
    MtfSignal trendRider = evaluateTrendRider(symbol, exchange, ind1h, ind3m, ctx1h, ctx3m,
        exhaustionBlock, macroEval, macroAdvice);

    if (trendRider.getStatus() == TradingEnums.StrategyStatus.ACTIVE) {
      return trendRider;
    }

    // 7. Tentar Sniper Scalp
    MtfSignal sniperScalp = evaluateSniperScalp(symbol, exchange, ind1h, ind3m, ctx1h, ctx3m,
        exhaustionBlock, macroEval, macroAdvice);

    if (sniperScalp.getStatus() == TradingEnums.StrategyStatus.ACTIVE) {
      return sniperScalp;
    }

    // 8. Nenhuma estratégia ativa — retorna o melhor "AGUARDANDO"
    // Prefere Trend Rider como referência de o que esperar
    return buildAguardando(symbol, exchange, ind1h, ind3m, ctx1h, ctx3m,
        exhaustionBlock, macroEval, macroAdvice, trendRider, sniperScalp);
  }

  // ─── Sniper Scalp ─────────────────────────────────────────────────────────

  private MtfSignal evaluateSniperScalp(String symbol, TradingEnums.Exchange exchange,
      IndicatorResult ind1h, IndicatorResult ind3m,
      MtfSignal.TimeframeContext ctx1h, MtfSignal.TimeframeContext ctx3m,
      boolean exhaustionBlock, String macroEval, String macroAdvice) {

    // ── Trava de Exaustão ────────────────────────────────────────────────────
    if (exhaustionBlock) {
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.SNIPER_SCALP,
          TradingEnums.StrategyStatus.BLOCKED,
          "🚫 Exaustão detectada: RSI 3m=" + String.format("%.1f", ind3m.getRsi()) +
              " acima de 75. Mercado subiu demais — aguardar recuo.",
          false, "1h neutro ou de alta — permissão base concedida para scalp",
          false, "RSI em exaustão — aguardar recuo para zona de pânico (30-43)",
          ctx1h, ctx3m, exhaustionBlock, true, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    // ── Permissão do 1h (O Chefe) ────────────────────────────────────────────
    // Regra: NÃO entrar se 1h estiver em QUEDA FORTE (não apanhar faca a cair)
    boolean permissionGranted = ind1h.getTrend() != TradingEnums.Trend.STRONG_DOWNTREND;
    String permissionReason;

    if (!permissionGranted) {
      permissionReason = "🚫 1h em QUEDA FORTE (" + ind1h.getTrend().getLabel() +
          "). Proibido fazer scalp contra tendência forte de baixa.";
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.SNIPER_SCALP,
          TradingEnums.StrategyStatus.BLOCKED,
          "1h em queda forte — Sniper Scalp bloqueado para proteger capital.",
          false, permissionReason,
          false, "Aguardar estabilização do 1h",
          ctx1h, ctx3m, exhaustionBlock, false, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    permissionReason = "✅ 1h está em " + ind1h.getTrend().getLabel() +
        " — não é queda forte, permissão concedida para scalp.";

    // ── Gatilho do 3m ────────────────────────────────────────────────────────
    boolean rsiInPanicZone = ind3m.getRsi() >= SNIPER_RSI_MIN && ind3m.getRsi() <= SNIPER_RSI_MAX;
    boolean macdBullishCross = "BULLISH_CROSS".equals(ind3m.getMacdCrossover());
    boolean triggerActive = rsiInPanicZone && macdBullishCross;

    String triggerReason;
    if (triggerActive) {
      triggerReason = "✅ Gatilho perfeito: RSI 3m=" + String.format("%.1f", ind3m.getRsi()) +
          " (pânico em " + SNIPER_RSI_MIN + "-" + SNIPER_RSI_MAX + ") + MACD Bullish Cross!";
    } else {
      List<String> missing = new ArrayList<>();
      if (!rsiInPanicZone) {
        if (ind3m.getRsi() < SNIPER_RSI_MIN)
          missing.add("RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " abaixo de " + SNIPER_RSI_MIN + " (pânico extremo)");
        else
          missing.add("RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " fora da zona de pânico (" + SNIPER_RSI_MIN + "-" + SNIPER_RSI_MAX + ")");
      }
      if (!macdBullishCross)
        missing.add("MACD 3m=" + ind3m.getMacdCrossover() + " — aguardar Bullish Cross (gancho de reversão)");
      triggerReason = "⏳ Aguardando gatilho: " + String.join("; ", missing);
    }

    if (!triggerActive) {
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.SNIPER_SCALP,
          TradingEnums.StrategyStatus.WAITING,
          "Permissão do 1h concedida. Aguardando gatilho no 3m.",
          true, permissionReason, false, triggerReason,
          ctx1h, ctx3m, exhaustionBlock, false, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    // ── SINAL ATIVO — calcular entrada e risco ───────────────────────────────
    double entryPrice = ind3m.getCurrentPrice();
    double stopLoss   = entryPrice * (1.0 - SNIPER_SL_PERCENT / 100.0);
    stopLoss = Math.round(stopLoss * 100.0) / 100.0;
    double slPct      = Math.abs(entryPrice - stopLoss) / entryPrice * 100;

    // TPs para o Sniper: rápidos, baseados no risco × R:R
    double risk  = Math.abs(entryPrice - stopLoss);
    double tp1   = Math.round((entryPrice + risk * 1.5) * 100.0) / 100.0; // R:R 1:1.5
    double tp2   = Math.round((entryPrice + risk * 3.0) * 100.0) / 100.0; // R:R 1:3
    double tp3   = Math.round((entryPrice + risk * 5.0) * 100.0) / 100.0; // R:R 1:5

    List<String> alerts = buildSniperAlerts(ind1h, ind3m);

    String description = String.format(
        "🎯 SNIPER SCALP ATIVO | RSI 3m=%.1f (pânico) + MACD Bullish Cross. " +
            "1h em %s — condição favorável. Meta: 5-10%% ROI em 2-15 min. " +
            "Alavancagem: %.0fx | SL: %.2f%%.",
        ind3m.getRsi(), ind1h.getTrend().getLabel(), SNIPER_LEVERAGE, SNIPER_SL_PERCENT);

    return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.SNIPER_SCALP,
        TradingEnums.StrategyStatus.ACTIVE,
        description,
        true, permissionReason, true, triggerReason,
        ctx1h, ctx3m, exhaustionBlock, false, macroEval, macroAdvice,
        ind1h, ind3m,
        buildRiskData(entryPrice, stopLoss, slPct, tp1, tp2, tp3,
            "Rápido (R:R 1:1.5)", "Principal Sniper (R:R 1:3)", "Máximo Sniper (R:R 1:5)",
            SNIPER_LEVERAGE, ind3m, "2min – 15min",
            "Scalp rápido: entrada no pânico do 3m, saída ao atingir TP1. " +
                "RSI=" + String.format("%.1f", ind3m.getRsi()) + " + MACD Bullish Cross confirmado."));
  }

  // ─── Trend Rider ──────────────────────────────────────────────────────────

  private MtfSignal evaluateTrendRider(String symbol, TradingEnums.Exchange exchange,
      IndicatorResult ind1h, IndicatorResult ind3m,
      MtfSignal.TimeframeContext ctx1h, MtfSignal.TimeframeContext ctx3m,
      boolean exhaustionBlock, String macroEval, String macroAdvice) {

    // ── Trava de Exaustão ────────────────────────────────────────────────────
    if (exhaustionBlock) {
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.TREND_RIDER,
          TradingEnums.StrategyStatus.BLOCKED,
          "🚫 Exaustão detectada: RSI 3m=" + String.format("%.1f", ind3m.getRsi()) +
              " > 75. Não entrar no topo.",
          false, "RSI 3m em exaustão — aguardar pullback",
          false, "RSI em exaustão — aguardar respiro para zona de equilíbrio (45-55)",
          ctx1h, ctx3m, exhaustionBlock, true, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    // ── Permissão do 1h (exigência máxima) ───────────────────────────────────
    boolean rsi1hBullish    = ind1h.getRsi() > RIDER_RSI_1H_MIN;
    boolean macd1hBullish   = ind1h.isMacdAboveZero() || "BULLISH_CROSS".equals(ind1h.getMacdCrossover());
    boolean trend1hBullish  = ind1h.getTrend() == TradingEnums.Trend.UPTREND
        || ind1h.getTrend() == TradingEnums.Trend.STRONG_UPTREND;
    boolean permissionGranted = rsi1hBullish && macd1hBullish && trend1hBullish;

    String permissionReason;
    if (!permissionGranted) {
      List<String> missing1h = new ArrayList<>();
      if (!rsi1hBullish)
        missing1h.add("RSI 1h=" + String.format("%.1f", ind1h.getRsi()) + " (precisa > " + RIDER_RSI_1H_MIN + ")");
      if (!macd1hBullish)
        missing1h.add("MACD 1h abaixo de zero sem cruzamento bullish");
      if (!trend1hBullish)
        missing1h.add("Tendência 1h=" + ind1h.getTrend().getLabel() + " (precisa de Alta ou Alta Forte)");

      permissionReason = "🚫 1h não atende exigência máxima: " + String.join("; ", missing1h);
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.TREND_RIDER,
          TradingEnums.StrategyStatus.BLOCKED,
          "1h não confirma tendência de alta — Trend Rider bloqueado.",
          false, permissionReason,
          false, "Aguardar força no 1h",
          ctx1h, ctx3m, exhaustionBlock, false, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    permissionReason = "✅ 1h confirma alta: RSI=" + String.format("%.1f", ind1h.getRsi()) +
        " > 50, MACD bullish, Tendência=" + ind1h.getTrend().getLabel();

    // ── Gatilho do 3m (pullback/respiro) ────────────────────────────────────
    boolean rsiInBreathZone = ind3m.getRsi() >= RIDER_RSI_MIN && ind3m.getRsi() <= RIDER_RSI_MAX;
    boolean macdBullishCross = "BULLISH_CROSS".equals(ind3m.getMacdCrossover());
    boolean triggerActive = rsiInBreathZone && macdBullishCross;

    String triggerReason;
    if (triggerActive) {
      triggerReason = "✅ Gatilho perfeito: RSI 3m=" + String.format("%.1f", ind3m.getRsi()) +
          " (equilíbrio " + RIDER_RSI_MIN + "-" + RIDER_RSI_MAX + ") + MACD Bullish Cross! " +
          "O respiro acabou — alta vai continuar.";
    } else {
      List<String> missing = new ArrayList<>();
      if (!rsiInBreathZone)
        missing.add("RSI 3m=" + String.format("%.1f", ind3m.getRsi()) +
            " fora da zona de equilíbrio (" + RIDER_RSI_MIN + "-" + RIDER_RSI_MAX + ") — aguardar pullback");
      if (!macdBullishCross)
        missing.add("MACD 3m=" + ind3m.getMacdCrossover() + " — aguardar Bullish Cross para confirmar fim do respiro");
      triggerReason = "⏳ Aguardando pullback: " + String.join("; ", missing);
    }

    if (!triggerActive) {
      return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.TREND_RIDER,
          TradingEnums.StrategyStatus.WAITING,
          "1h confirma alta. Aguardando pullback/respiro no 3m para entrar.",
          true, permissionReason, false, triggerReason,
          ctx1h, ctx3m, exhaustionBlock, !macdBullishCross, macroEval, macroAdvice,
          ind1h, ind3m, null);
    }

    // ── SINAL ATIVO — calcular entrada e risco ───────────────────────────────
    double entryPrice = ind3m.getCurrentPrice();
    double stopLoss   = entryPrice * (1.0 - RIDER_SL_PERCENT / 100.0);
    stopLoss = Math.round(stopLoss * 100.0) / 100.0;

    // Afina o SL técnico usando mínimo recente
    double technicalSL = calculateTechnicalSL(entryPrice, RIDER_SL_PERCENT);
    stopLoss = technicalSL;

    double slPct = Math.abs(entryPrice - stopLoss) / entryPrice * 100;

    // TPs para o Trend Rider: grande, baseados na onda
    double risk = Math.abs(entryPrice - stopLoss);
    double tp1  = Math.round((entryPrice + risk * 2.0) * 100.0) / 100.0;  // R:R 1:2 (parcial aqui)
    double tp2  = Math.round((entryPrice + risk * 4.0) * 100.0) / 100.0;  // R:R 1:4 (meta principal)
    double tp3  = Math.round((entryPrice + risk * 6.0) * 100.0) / 100.0;  // R:R 1:6 (surf completo)

    List<String> alerts = buildTrendRiderAlerts(ind1h, ind3m);

    String description = String.format(
        "🚢 TREND RIDER ATIVO | 1h em %s (RSI=%.1f, MACD bullish). " +
            "3m fez respiro (RSI=%.1f) + Bullish Cross — onda vai continuar! " +
            "Meta: 20-50%%+ ROI em 1-6h. Alavancagem: %.0fx | SL: %.2f%% (técnico).",
        ind1h.getTrend().getLabel(), ind1h.getRsi(),
        ind3m.getRsi(), RIDER_LEVERAGE, slPct);

    return buildMtfSignal(symbol, exchange, TradingEnums.StrategyType.TREND_RIDER,
        TradingEnums.StrategyStatus.ACTIVE,
        description,
        true, permissionReason, true, triggerReason,
        ctx1h, ctx3m, exhaustionBlock, false, macroEval, macroAdvice,
        ind1h, ind3m,
        buildRiskData(entryPrice, stopLoss, slPct, tp1, tp2, tp3,
            "Parcial 30-50% (R:R 1:2) — mova SL para entrada",
            "Alvo Principal (R:R 1:4) — deixe correr",
            "Surf Completo (R:R 1:6) — risco zero após TP1",
            RIDER_LEVERAGE, ind3m, "1h – 6h",
            "Trend Rider: entrada no respiro do 3m, onda de alta do 1h ainda ativa. " +
                "Ao atingir TP1: realizar 30-50% e mover SL para entrada (risco zero)."));
  }

  // ─── Construção de AGUARDANDO ─────────────────────────────────────────────

  private MtfSignal buildAguardando(String symbol, TradingEnums.Exchange exchange,
      IndicatorResult ind1h, IndicatorResult ind3m,
      MtfSignal.TimeframeContext ctx1h, MtfSignal.TimeframeContext ctx3m,
      boolean exhaustionBlock, String macroEval, String macroAdvice,
      MtfSignal trendRiderRef, MtfSignal sniperRef) {

    // Determina qual estratégia tem mais chance de ativar
    TradingEnums.StrategyType bestCandidate;
    String waitingReason;
    List<String> waitingConditions = new ArrayList<>();

    boolean trendRiderBlocked = trendRiderRef.getStatus() == TradingEnums.StrategyStatus.BLOCKED;
    boolean sniperBlocked     = sniperRef.getStatus()     == TradingEnums.StrategyStatus.BLOCKED;

    if (!trendRiderBlocked) {
      bestCandidate = TradingEnums.StrategyType.TREND_RIDER;
      waitingReason = "Nenhuma estratégia ativa. Trend Rider tem permissão do 1h — aguardando pullback no 3m.";
      waitingConditions.add(trendRiderRef.getTriggerReason());
    } else if (!sniperBlocked) {
      bestCandidate = TradingEnums.StrategyType.SNIPER_SCALP;
      waitingReason = "Nenhuma estratégia ativa. Sniper tem permissão — aguardando zona de pânico no 3m.";
      waitingConditions.add(sniperRef.getTriggerReason());
    } else {
      bestCandidate = TradingEnums.StrategyType.AGUARDANDO;
      waitingReason = "Ambas as estratégias bloqueadas. " +
          (exhaustionBlock ? "Mercado em exaustão (RSI > 75)." : "Condições de 1h desfavoráveis.");
      waitingConditions.add("Aguardar recuo geral do mercado");
      if (exhaustionBlock)
        waitingConditions.add("RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " acima de 75 — exaustão");
    }

    // Adiciona condições gerais
    if ("BEARISH".equals(macroEval))
      waitingConditions.add("⚠️ Macro BEARISH — focar apenas em Sniper Scalp super rápidos");
    if (exhaustionBlock)
      waitingConditions.add("RSI 3m exausto (>" + EXHAUSTION_RSI + ") — aguardar recuo para zonas de entrada");

    return MtfSignal.builder()
        .symbol(symbol)
        .exchange(exchange.name())
        .generatedAt(Instant.now())
        .strategyType(bestCandidate)
        .strategyLabel(bestCandidate.getLabel())
        .strategyDescription(waitingReason)
        .status(TradingEnums.StrategyStatus.WAITING)
        .statusReason(waitingReason)
        .context1h(ctx1h)
        .context3m(ctx3m)
        .permissionGranted(trendRiderRef.isPermissionGranted() || sniperRef.isPermissionGranted())
        .permissionReason("Ver condições de espera")
        .triggerActive(false)
        .triggerReason("Nenhum gatilho ativo no 3m")
        .exhaustionBlock(exhaustionBlock)
        .macdConfirmationMissing(!("BULLISH_CROSS".equals(ind3m.getMacdCrossover())))
        .macroEvaluation(macroEval)
        .macroAdvice(macroAdvice)
        .waitingConditions(waitingConditions)
        .alerts(buildGeneralAlerts(ind1h, ind3m, macroEval, exhaustionBlock))
        .entryPrice(ind3m.getCurrentPrice())
        .build();
  }

  // ─── Utilitários ──────────────────────────────────────────────────────────

  private double calculateTechnicalSL(double entryPrice, double defaultSlPercent) {
    double sl = entryPrice * (1.0 - defaultSlPercent / 100.0);
    return Math.round(sl * 100.0) / 100.0;
  }

  private String evaluateMacro(IndicatorResult ind1h) {
    boolean isUptrend = ind1h.getTrend() == TradingEnums.Trend.UPTREND
        || ind1h.getTrend() == TradingEnums.Trend.STRONG_UPTREND;
    boolean isDowntrend = ind1h.getTrend() == TradingEnums.Trend.DOWNTREND
        || ind1h.getTrend() == TradingEnums.Trend.STRONG_DOWNTREND;

    if (isUptrend && ind1h.getRsi() > 50) return "BULLISH";
    if (isDowntrend && ind1h.getRsi() < 50) return "BEARISH";
    return "NEUTRAL";
  }

  private String buildMacroAdvice(String macroEval) {
    return switch (macroEval) {
      case "BULLISH" -> "✅ Macro BULLISH: Ambas as estratégias liberadas. Trend Rider tem prioridade.";
      case "BEARISH" -> "⚠️ Macro BEARISH: Ignorar Trend Rider. Focar apenas em Sniper Scalp super rápidos com meta conservadora.";
      default -> "↔️ Macro NEUTRO: Priorizar Sniper Scalp. Trend Rider apenas se 1h recuperar força.";
    };
  }

  private MtfSignal.TimeframeContext buildContext(String interval, IndicatorResult ind) {
    return MtfSignal.TimeframeContext.builder()
        .interval(interval)
        .currentPrice(ind.getCurrentPrice())
        .rsi(Math.round(ind.getRsi() * 100.0) / 100.0)
        .rsiSignal(ind.getRsiSignal())
        .macdCrossover(ind.getMacdCrossover())
        .macdLine(Math.round(ind.getMacdLine() * 10000.0) / 10000.0)
        .macdSignalLine(Math.round(ind.getMacdSignalLine() * 10000.0) / 10000.0)
        .macdHistogram(Math.round(ind.getMacdHistogram() * 10000.0) / 10000.0)
        .macdAboveZero(ind.isMacdAboveZero())
        .trend(ind.getTrend())
        .trendLabel(ind.getTrend().getLabel())
        .ema9(Math.round(ind.getEma9() * 100.0) / 100.0)
        .ema21(Math.round(ind.getEma21() * 100.0) / 100.0)
        .ema50(Math.round(ind.getEma50() * 100.0) / 100.0)
        .emaTrend(ind.getEmaTrend())
        .volumeRatio(Math.round(ind.getVolumeRatio() * 100.0) / 100.0)
        .highVolume(ind.isHighVolume())
        .build();
  }

  private record RiskData(
      double entryPrice, double stopLoss, double slPct,
      double tp1, double tp2, double tp3,
      String tp1Label, String tp2Label, String tp3Label,
      double leverage, double rrRatio,
      TradingEnums.RiskLevel riskLevel,
      String estimatedDuration, String durationBasis
  ) {}

  private RiskData buildRiskData(double entryPrice, double stopLoss, double slPct,
      double tp1, double tp2, double tp3,
      String tp1Label, String tp2Label, String tp3Label,
      double leverage, IndicatorResult ind3m,
      String estimatedDuration, String durationBasis) {

    double risk   = Math.abs(tp2 - entryPrice);
    double riskSl = Math.abs(entryPrice - stopLoss);
    double rr     = riskSl > 0 ? Math.round((risk / riskSl) * 100.0) / 100.0 : 0;

    TradingEnums.RiskLevel riskLevel = riskService.assessRiskLevel(ind3m, 0.7);

    return new RiskData(entryPrice, stopLoss, slPct, tp1, tp2, tp3,
        tp1Label, tp2Label, tp3Label, leverage, rr, riskLevel,
        estimatedDuration, durationBasis);
  }

  private MtfSignal buildMtfSignal(String symbol, TradingEnums.Exchange exchange,
      TradingEnums.StrategyType strategyType,
      TradingEnums.StrategyStatus status,
      String description,
      boolean permissionGranted, String permissionReason,
      boolean triggerActive, String triggerReason,
      MtfSignal.TimeframeContext ctx1h, MtfSignal.TimeframeContext ctx3m,
      boolean exhaustionBlock, boolean macdMissing,
      String macroEval, String macroAdvice,
      IndicatorResult ind1h, IndicatorResult ind3m,
      RiskData riskData) {

    MtfSignal.MtfSignalBuilder builder = MtfSignal.builder()
        .symbol(symbol)
        .exchange(exchange.name())
        .generatedAt(Instant.now())
        .strategyType(strategyType)
        .strategyLabel(strategyType.getLabel())
        .strategyDescription(description)
        .status(status)
        .statusReason(description)
        .context1h(ctx1h)
        .context3m(ctx3m)
        .permissionGranted(permissionGranted)
        .permissionReason(permissionReason)
        .triggerActive(triggerActive)
        .triggerReason(triggerReason)
        .exhaustionBlock(exhaustionBlock)
        .macdConfirmationMissing(macdMissing)
        .macroEvaluation(macroEval)
        .macroAdvice(macroAdvice)
        .entryPrice(ind3m.getCurrentPrice());

    if (riskData != null) {
      double rrRatio = Math.abs(riskData.tp2() - riskData.entryPrice()) /
          Math.max(Math.abs(riskData.entryPrice() - riskData.stopLoss()), 0.0001);

      builder
          .entryPrice(riskData.entryPrice())
          .entryZoneLow(Math.round(riskData.entryPrice() * 0.999 * 100.0) / 100.0)
          .entryZoneHigh(Math.round(riskData.entryPrice() * 1.001 * 100.0) / 100.0)
          .stopLoss(riskData.stopLoss())
          .stopLossPercent(Math.round(riskData.slPct() * 100.0) / 100.0)
          .stopLossRationale(buildSlRationale(strategyType, riskData.entryPrice(),
              riskData.stopLoss(), riskData.slPct()))
          .takeProfit1(riskData.tp1())
          .takeProfit2(riskData.tp2())
          .takeProfit3(riskData.tp3())
          .tp1Label(riskData.tp1Label())
          .tp2Label(riskData.tp2Label())
          .tp3Label(riskData.tp3Label())
          .riskRewardRatio(Math.round(rrRatio * 100.0) / 100.0)
          .suggestedLeverage(riskData.leverage())
          .riskLevel(riskData.riskLevel())
          .riskLevelLabel(riskData.riskLevel().getLabel())
          .estimatedDuration(riskData.estimatedDuration())
          .durationBasis(riskData.durationBasis());

      if (strategyType == TradingEnums.StrategyType.SNIPER_SCALP) {
        builder.alerts(buildSniperAlerts(ind1h, ind3m));
      } else {
        builder.alerts(buildTrendRiderAlerts(ind1h, ind3m));
      }
    } else {
      builder.alerts(buildGeneralAlerts(ind1h, ind3m, macroEval, exhaustionBlock));
      List<String> waiting = new ArrayList<>();
      if (!permissionGranted) waiting.add(permissionReason);
      if (!triggerActive) waiting.add(triggerReason);
      builder.waitingConditions(waiting);
    }

    return builder.build();
  }

  private String buildSlRationale(TradingEnums.StrategyType strategy,
      double entry, double sl, double slPct) {
    if (strategy == TradingEnums.StrategyType.SNIPER_SCALP) {
      return String.format("SL Sniper: %.2f%% abaixo da entrada (%.4f → %.4f). " +
          "Tiro curto — invalidar rapidamente se o preço não reagir.", slPct, entry, sl);
    } else {
      return String.format("SL Trend Rider: %.2f%% abaixo da entrada (%.4f → %.4f), " +
          "abaixo do fundo anterior. Invalida a trade se fechar abaixo deste nível.", slPct, entry, sl);
    }
  }

  private List<String> buildSniperAlerts(IndicatorResult ind1h, IndicatorResult ind3m) {
    List<String> alerts = new ArrayList<>();
    alerts.add("🎯 SNIPER SCALP: Entrar, bater a meta e sair rapidamente (2-15 min).");
    alerts.add("📌 RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " na zona de pânico (30-43) — alta probabilidade de repique.");
    alerts.add("🔗 MACD 3m cruzou para cima — reversão de curto prazo confirmada.");
    if (ind1h.getTrend() == TradingEnums.Trend.UPTREND || ind1h.getTrend() == TradingEnums.Trend.STRONG_UPTREND)
      alerts.add("💚 Bônus: 1h em tendência de alta — scalp a favor da tendência maior.");
    alerts.add("⚡ Alavancagem: 15x | SL: ~0.8% | Meta: TP1 rápido e sair.");
    alerts.add("🚨 Ao atingir TP1: fechar 100% da posição. Não ser ganancioso neste scalp!");
    return alerts;
  }

  private List<String> buildTrendRiderAlerts(IndicatorResult ind1h, IndicatorResult ind3m) {
    List<String> alerts = new ArrayList<>();
    alerts.add("🚢 TREND RIDER: A onda está formada no 1h — entrar no respiro do 3m.");
    alerts.add("📊 1h confirma alta: RSI=" + String.format("%.1f", ind1h.getRsi()) +
        " > 50, tendência=" + ind1h.getTrend().getLabel());
    alerts.add("🌊 RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " na zona de equilíbrio — respiro acabou, alta retoma.");
    alerts.add("🔗 MACD 3m Bullish Cross — confirmação do fim do pullback.");
    alerts.add("⚓ Alavancagem: 10x | SL: ~2.5% técnico | Dar espaço ao preço.");
    alerts.add("🎯 Ao atingir TP1 (20% ROI): realizar 30-50% e mover SL para entrada (RISCO ZERO).");
    alerts.add("🏄 Deixar o restante correr até TP2/TP3 com risco zero — surf completo!");
    return alerts;
  }

  private List<String> buildGeneralAlerts(IndicatorResult ind1h, IndicatorResult ind3m,
      String macroEval, boolean exhaustionBlock) {
    List<String> alerts = new ArrayList<>();
    if (exhaustionBlock)
      alerts.add("🚫 Exaustão: RSI 3m=" + String.format("%.1f", ind3m.getRsi()) + " > 75. Aguardar recuo antes de entrar.");
    if ("BEARISH".equals(macroEval))
      alerts.add("⚠️ Macro BEARISH: Evitar Trend Rider. Apenas Sniper Scalp rápido se RSI 3m cair para 30-43.");
    alerts.add("💡 1h: RSI=" + String.format("%.1f", ind1h.getRsi()) +
        " | Tendência=" + ind1h.getTrend().getLabel() + " | MACD=" + ind1h.getMacdCrossover());
    alerts.add("💡 3m: RSI=" + String.format("%.1f", ind3m.getRsi()) +
        " | MACD=" + ind3m.getMacdCrossover());
    return alerts;
  }

  private List<Candlestick> fetchCandles(String symbol, String interval,
      TradingEnums.Exchange exchange, int limit) {
    return switch (exchange) {
      case BINANCE -> binanceApiService.getKlines(symbol, interval, limit);
      case BYBIT   -> bybitApiService.getKlines(symbol, interval, limit);
    };
  }
}
