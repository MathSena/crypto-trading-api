package com.mathsena.cryptotradingapi.service;


import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Serviço de gestão de risco: calcula Stop Loss, Take Profit,
 * tamanho de posição, alavancagem recomendada e avisos.
 */
@Service
public class RiskManagementService {

  @Value("${risk.default-stop-loss-percent:2.0}")
  private double defaultStopLossPercent;

  @Value("${risk.default-take-profit-multiplier:2.0}")
  private double defaultTakeProfitMultiplier;

  @Value("${risk.max-risk-per-trade:1.0}")
  private double maxRiskPercent;

  // ─── Stop Loss ────────────────────────────────────────────────────────────

  /**
   * Calcula o Stop Loss baseado na estrutura técnica (swing low/high).
   *
   * Regras invioláveis:
   *  - Compra (LONG): SL deve estar SEMPRE abaixo do preço de entrada
   *  - Venda (SHORT): SL deve estar SEMPRE acima do preço de entrada
   *
   * Lógica:
   *  1. Tenta usar suporte/resistência técnico (swing low/high e EMA50)
   *  2. EMA50 só é usada se estiver no lado correto da entrada
   *  3. Se o cálculo técnico violar a regra, usa o SL padrão por %
   *  4. O SL nunca fica mais distante que defaultStopLossPercent% da entrada
   */
  public double calculateStopLoss(double entryPrice, boolean isBuy,
      IndicatorResult indicators,
      double recentLow, double recentHigh) {

    // SL padrão por % — funciona como âncora de segurança
    double defaultSL = isBuy
        ? entryPrice * (1.0 - defaultStopLossPercent / 100.0)
        : entryPrice * (1.0 + defaultStopLossPercent / 100.0);

    // SL mínimo absoluto: nunca menos de 1% da entrada (evita SL ridículo em ativos voláteis)
    double minSlDistance = entryPrice * 0.01;
    double minSL = isBuy
        ? entryPrice - minSlDistance
        : entryPrice + minSlDistance;

    double technicalSL;

    if (isBuy) {
      // ── LONG: SL deve ficar ABAIXO da entrada ──────────────────────────
      double swingLow = recentLow  * 0.998; // swing low com 0.2% de buffer
      double ema50    = indicators.getEma50();

      if (ema50 < entryPrice) {
        // EMA50 está abaixo da entrada — pode ser usada como suporte
        double ema50SL = ema50 * 0.998;
        // Usa o nível mais próximo da entrada (menor risco)
        technicalSL = Math.max(swingLow, ema50SL);
      } else {
        // EMA50 está acima da entrada (impulso forte) — usa só o swing low
        technicalSL = swingLow;
      }

      // Validação final: garante que SL está abaixo da entrada
      if (technicalSL >= entryPrice) {
        technicalSL = defaultSL;
      }

      // Garante que SL não fique mais longe que o padrão por %
      technicalSL = Math.max(technicalSL, defaultSL);

    } else {
      // ── SHORT: SL deve ficar ACIMA da entrada ──────────────────────────
      double swingHigh = recentHigh * 1.002; // swing high com 0.2% de buffer
      double ema50     = indicators.getEma50();

      if (ema50 > entryPrice) {
        // EMA50 está acima da entrada — pode ser usada como resistência
        double ema50SL = ema50 * 1.002;
        // Usa o nível mais próximo da entrada (menor risco)
        technicalSL = Math.min(swingHigh, ema50SL);
      } else {
        // EMA50 está abaixo da entrada (queda forte) — usa só o swing high
        technicalSL = swingHigh;
      }

      // Validação final: garante que SL está acima da entrada
      if (technicalSL <= entryPrice) {
        technicalSL = defaultSL;
      }

      // Garante que SL não fique mais longe que o padrão por %
      technicalSL = Math.min(technicalSL, defaultSL);
    }

    // Aplica o SL mínimo — garante ao menos 1% de distância da entrada
    if (isBuy) {
      technicalSL = Math.min(technicalSL, minSL);  // não deixa SL ficar mais perto que 1%
    } else {
      technicalSL = Math.max(technicalSL, minSL);
    }

    return Math.round(technicalSL * 100.0) / 100.0;
  }

  public String getStopLossRationale(double entryPrice, double stopLoss, boolean isBuy,
      IndicatorResult indicators) {
    double slPercent = Math.abs((stopLoss - entryPrice) / entryPrice * 100);

    if (isBuy) {
      return String.format("SL posicionado %.2f%% abaixo da entrada (%.2f), " +
          "próximo ao suporte da EMA50 (%.2f) e mínimo recente. " +
          "Invalida a trade se o preço fechar abaixo.", slPercent, entryPrice, indicators.getEma50());
    } else {
      return String.format("SL posicionado %.2f%% acima da entrada (%.2f), " +
          "próximo à resistência da EMA50 (%.2f) e máximo recente. " +
          "Invalida a trade se o preço fechar acima.", slPercent, entryPrice, indicators.getEma50());
    }
  }

  // ─── Take Profit ──────────────────────────────────────────────────────────

  /**
   * Calcula 3 alvos de Take Profit baseados no risco (Risk:Reward).
   * TP1 = 1:1 (conservador), TP2 = 1:2 (principal), TP3 = 1:3 (agressivo).
   *
   * Regras invioláveis:
   *  - Compra: todos os TPs devem estar ACIMA da entrada
   *  - Venda:  todos os TPs devem estar ABAIXO da entrada
   */
  public double[] calculateTakeProfits(double entryPrice, double stopLoss, boolean isBuy) {
    double risk = Math.abs(entryPrice - stopLoss);

    // Garante que o risco é positivo e razoável (mínimo 0.1% da entrada)
    if (risk < entryPrice * 0.001) {
      risk = entryPrice * 0.01; // fallback: 1% da entrada
    }

    double[] tps = new double[3];
    if (isBuy) {
      tps[0] = entryPrice + (risk * 1.0); // TP1: R:R 1:1
      tps[1] = entryPrice + (risk * 2.0); // TP2: R:R 1:2
      tps[2] = entryPrice + (risk * 3.0); // TP3: R:R 1:3
    } else {
      tps[0] = entryPrice - (risk * 1.0);
      tps[1] = entryPrice - (risk * 2.0);
      tps[2] = entryPrice - (risk * 3.0);
    }

    for (int i = 0; i < 3; i++) {
      tps[i] = Math.round(tps[i] * 100.0) / 100.0;
    }

    return tps;
  }

  // ─── Alavancagem e Posição ────────────────────────────────────────────────

  /**
   * Sugere alavancagem baseada no % de SL e volatilidade do mercado.
   */
  public double suggestLeverage(IndicatorResult indicators, double slPercent) {
    double baseLeverage;

    if (slPercent <= 0.5) baseLeverage = 20;
    else if (slPercent <= 1.0) baseLeverage = 10;
    else if (slPercent <= 1.5) baseLeverage = 7;
    else if (slPercent <= 2.0) baseLeverage = 5;
    else if (slPercent <= 3.0) baseLeverage = 3;
    else baseLeverage = 2;

    if (indicators.getTrend() == TradingEnums.Trend.SIDEWAYS) {
      baseLeverage = Math.max(1, baseLeverage * 0.7);
    }

    if (indicators.getRsi() > 80 || indicators.getRsi() < 20) {
      baseLeverage = Math.max(1, baseLeverage * 0.8);
    }

    return Math.round(baseLeverage);
  }

  /**
   * Calcula o tamanho da posição em % do capital.
   */
  public double calculatePositionSize(double entryPrice, double stopLoss,
      double accountBalance, double riskPercent) {
    if (accountBalance <= 0) return maxRiskPercent;

    double risk = Math.abs(riskPercent > 0 ? riskPercent : maxRiskPercent);
    double riskAmount = accountBalance * (risk / 100);
    double slDistance = Math.abs(entryPrice - stopLoss) / entryPrice;

    if (slDistance <= 0) return 0;

    double positionSize = (riskAmount / (accountBalance * slDistance)) * 100;
    return Math.min(Math.round(positionSize * 10.0) / 10.0, 100.0);
  }

  // ─── Nível de Risco ───────────────────────────────────────────────────────

  public TradingEnums.RiskLevel assessRiskLevel(IndicatorResult indicators, double signalStrength) {
    int riskScore = 0;

    if (indicators.getTrend() == TradingEnums.Trend.SIDEWAYS) riskScore += 2;
    if (indicators.getVolumeRatio() < 0.7) riskScore += 1;
    if (indicators.getRsi() > 75 || indicators.getRsi() < 25) riskScore += 2;
    if (signalStrength < 0.5) riskScore += 2;
    if ("NONE".equals(indicators.getMacdCrossover())) riskScore += 1;

    if (riskScore >= 6) return TradingEnums.RiskLevel.EXTREME;
    if (riskScore >= 4) return TradingEnums.RiskLevel.HIGH;
    if (riskScore >= 2) return TradingEnums.RiskLevel.MEDIUM;
    return TradingEnums.RiskLevel.LOW;
  }

  // ─── Alertas de Saída ────────────────────────────────────────────────────

  public List<String> generateExitWarnings(IndicatorResult indicators, boolean isBuySignal) {
    List<String> warnings = new ArrayList<>();

    if (isBuySignal) {
      if (indicators.getRsi() > 70) {
        warnings.add("⚠️ RSI (" + String.format("%.1f", indicators.getRsi())
            + ") em sobrecompra — considere reduzir posição ou mover SL para breakeven.");
      }
      if (indicators.getMacdHistogram() < 0 && !"BEARISH_CROSS".equals(indicators.getMacdCrossover())) {
        warnings.add("⚠️ Histograma MACD enfraquecendo — momento comprador perdendo força.");
      }
      if (indicators.getEma9() < indicators.getEma21()) {
        warnings.add("⚠️ EMA9 cruzou abaixo da EMA21 — sinal de reversão de curto prazo.");
      }
    } else {
      if (indicators.getRsi() < 30) {
        warnings.add("⚠️ RSI (" + String.format("%.1f", indicators.getRsi())
            + ") em sobrevenda — considere realizar lucros parciais.");
      }
      if (indicators.getMacdHistogram() > 0) {
        warnings.add("⚠️ Histograma MACD subindo — pressão compradora aumentando.");
      }
      if (indicators.getEma9() > indicators.getEma21()) {
        warnings.add("⚠️ EMA9 cruzou acima da EMA21 — possível reversão de alta.");
      }
    }

    if (!indicators.isHighVolume()) {
      warnings.add("ℹ️ Volume abaixo da média — movimento pode não ter convicção.");
    }

    return warnings;
  }

  public List<String> generateTargetAdjustments(double entryPrice, double currentPrice,
      double tp1, double tp2, boolean isBuySignal) {
    List<String> adjustments = new ArrayList<>();

    double progressToTP1 = isBuySignal
        ? (currentPrice - entryPrice) / (tp1 - entryPrice)
        : (entryPrice - currentPrice) / (entryPrice - tp1);

    if (progressToTP1 >= 0.8) {
      adjustments.add("🎯 TP1 quase atingido — mova o SL para breakeven (preço de entrada).");
      adjustments.add("🎯 Considere realizar 30-50% da posição no TP1.");
    }

    if (progressToTP1 >= 1.0) {
      adjustments.add("✅ TP1 atingido! SL deve estar no breakeven agora.");
      adjustments.add("🎯 Deixe o restante correr até TP2 com risco zero.");
    }

    return adjustments;
  }

  public boolean shouldExitNow(IndicatorResult indicators, boolean isBuySignal) {
    if (isBuySignal) {
      return "BEARISH_CROSS".equals(indicators.getMacdCrossover())
          && indicators.getRsi() > 70
          && indicators.getEma9() < indicators.getEma21();
    } else {
      return "BULLISH_CROSS".equals(indicators.getMacdCrossover())
          && indicators.getRsi() < 30
          && indicators.getEma9() > indicators.getEma21();
    }
  }

  public String getExitReason(IndicatorResult indicators, boolean isBuySignal) {
    if (shouldExitNow(indicators, isBuySignal)) {
      if (isBuySignal) {
        return "Múltiplos sinais de reversão de baixa: MACD bearish cross + RSI sobrecomprado + EMA cruzada para baixo.";
      } else {
        return "Múltiplos sinais de reversão de alta: MACD bullish cross + RSI sobrevendido + EMA cruzada para cima.";
      }
    }
    return null;
  }
}