package com.mathsena.cryptotradingapi.service;


import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Serviço de scoring e ranqueamento de ativos.
 *
 * O score composto (0-100) é calculado com os seguintes pesos:
 * ┌─────────────────────┬────────┐
 * │ Componente          │  Peso  │
 * ├─────────────────────┼────────┤
 * │ MACD (crossover)    │  30%   │
 * │ RSI (posição/força) │  25%   │
 * │ Tendência (EMAs)    │  25%   │
 * │ Volume              │  10%   │
 * │ Momentum (24h)      │  10%   │
 * └─────────────────────┴────────┘
 */
@Service
public class AssetScoringService {

  private static final Logger log = LoggerFactory.getLogger(AssetScoringService.class);

  /**
   * Calcula o score composto para COMPRA (0–100).
   * Quanto maior, melhor o sinal de compra.
   */
  public double calculateBuyScore(IndicatorResult ind) {
    double rsiScore     = calcRsiScoreBuy(ind.getRsi());
    double macdScore    = calcMacdScoreBuy(ind);
    double trendScore   = calcTrendScore(ind, true);
    double volumeScore  = calcVolumeScore(ind.getVolumeRatio());
    double momentumScore = calcMomentumScore(ind.getPriceChangePercent24h(), true);

    return (macdScore * 0.30)
        + (rsiScore  * 0.25)
        + (trendScore * 0.25)
        + (volumeScore * 0.10)
        + (momentumScore * 0.10);
  }

  /**
   * Calcula o score composto para VENDA (0–100).
   * Quanto maior, melhor o sinal de venda.
   */
  public double calculateSellScore(IndicatorResult ind) {
    double rsiScore      = calcRsiScoreSell(ind.getRsi());
    double macdScore     = calcMacdScoreSell(ind);
    double trendScore    = calcTrendScore(ind, false);
    double volumeScore   = calcVolumeScore(ind.getVolumeRatio());
    double momentumScore = calcMomentumScore(ind.getPriceChangePercent24h(), false);

    return (macdScore * 0.30)
        + (rsiScore  * 0.25)
        + (trendScore * 0.25)
        + (volumeScore * 0.10)
        + (momentumScore * 0.10);
  }

  // ─── RSI ──────────────────────────────────────────────────────────────────

  private double calcRsiScoreBuy(double rsi) {
    // RSI baixo (sobrevenda) = ótimo para compra
    if (rsi <= 20) return 100;
    if (rsi <= 25) return 90;
    if (rsi <= 30) return 80;
    if (rsi <= 35) return 65;
    if (rsi <= 40) return 50;
    if (rsi <= 45) return 35;
    if (rsi <= 50) return 25;
    if (rsi <= 60) return 15;
    if (rsi <= 70) return 5;
    return 0; // RSI alto = péssimo para compra
  }

  private double calcRsiScoreSell(double rsi) {
    // RSI alto (sobrecompra) = ótimo para venda
    if (rsi >= 80) return 100;
    if (rsi >= 75) return 90;
    if (rsi >= 70) return 80;
    if (rsi >= 65) return 65;
    if (rsi >= 60) return 50;
    if (rsi >= 55) return 35;
    if (rsi >= 50) return 25;
    if (rsi >= 40) return 15;
    if (rsi >= 30) return 5;
    return 0;
  }

  // ─── MACD ─────────────────────────────────────────────────────────────────

  private double calcMacdScoreBuy(IndicatorResult ind) {
    double score = 0;

    // Crossover bullish recente = máximo score
    if ("BULLISH_CROSS".equals(ind.getMacdCrossover())) score += 60;

    // MACD acima da linha de sinal
    if (ind.getMacdLine() > ind.getMacdSignalLine()) score += 25;

    // Histograma positivo e crescendo
    if (ind.getMacdHistogram() > 0) score += 15;

    // MACD acima de zero (momentum de alta)
    if (ind.isMacdAboveZero()) score += 10;

    // Bearish cross penaliza
    if ("BEARISH_CROSS".equals(ind.getMacdCrossover())) score = Math.max(0, score - 40);

    return Math.min(score, 100);
  }

  private double calcMacdScoreSell(IndicatorResult ind) {
    double score = 0;

    if ("BEARISH_CROSS".equals(ind.getMacdCrossover())) score += 60;
    if (ind.getMacdLine() < ind.getMacdSignalLine()) score += 25;
    if (ind.getMacdHistogram() < 0) score += 15;
    if (!ind.isMacdAboveZero()) score += 10;
    if ("BULLISH_CROSS".equals(ind.getMacdCrossover())) score = Math.max(0, score - 40);

    return Math.min(score, 100);
  }

  // ─── Tendência (EMA) ──────────────────────────────────────────────────────

  private double calcTrendScore(IndicatorResult ind, boolean forBuy) {
    TradingEnums.Trend trend = ind.getTrend();

    if (forBuy) {
      return switch (trend) {
        case STRONG_UPTREND -> 100;
        case UPTREND -> 70;
        case SIDEWAYS -> 30;
        case DOWNTREND -> 10;
        case STRONG_DOWNTREND -> 0;
      };
    } else {
      return switch (trend) {
        case STRONG_DOWNTREND -> 100;
        case DOWNTREND -> 70;
        case SIDEWAYS -> 30;
        case UPTREND -> 10;
        case STRONG_UPTREND -> 0;
      };
    }
  }

  // ─── Volume ───────────────────────────────────────────────────────────────

  private double calcVolumeScore(double volumeRatio) {
    // Volume alto confirma o movimento → score maior
    if (volumeRatio >= 3.0) return 100;
    if (volumeRatio >= 2.0) return 85;
    if (volumeRatio >= 1.5) return 70;
    if (volumeRatio >= 1.2) return 55;
    if (volumeRatio >= 1.0) return 40;
    if (volumeRatio >= 0.7) return 20;
    return 5; // Volume muito baixo = sinal sem convicção
  }

  // ─── Momentum (variação 24h) ───────────────────────────────────────────────

  private double calcMomentumScore(double priceChangePercent, boolean forBuy) {
    if (forBuy) {
      // Para compra: queda forte = oportunidade de reversão
      if (priceChangePercent <= -10) return 90;
      if (priceChangePercent <= -5) return 75;
      if (priceChangePercent <= -2) return 60;
      if (priceChangePercent <= 0) return 45;
      if (priceChangePercent <= 3) return 35;
      if (priceChangePercent <= 7) return 20;
      return 10; // Alta muito forte = possível sobrecompra
    } else {
      // Para venda: alta forte = oportunidade de reversão
      if (priceChangePercent >= 10) return 90;
      if (priceChangePercent >= 5) return 75;
      if (priceChangePercent >= 2) return 60;
      if (priceChangePercent >= 0) return 45;
      if (priceChangePercent >= -3) return 35;
      if (priceChangePercent >= -7) return 20;
      return 10;
    }
  }

  // ─── Grade ────────────────────────────────────────────────────────────────

  public String getScoreGrade(double score) {
    if (score >= 85) return "A+";
    if (score >= 75) return "A";
    if (score >= 65) return "B+";
    if (score >= 55) return "B";
    if (score >= 45) return "C";
    return "D";
  }

  // ─── Razão do Ranking ────────────────────────────────────────────────────

  public String buildRankingReason(IndicatorResult ind, boolean isBuy, int rank) {
    List<String> reasons = new ArrayList<>();

    if ("BULLISH_CROSS".equals(ind.getMacdCrossover()) && isBuy)
      reasons.add("MACD Bullish Cross recente");
    if ("BEARISH_CROSS".equals(ind.getMacdCrossover()) && !isBuy)
      reasons.add("MACD Bearish Cross recente");

    if (ind.getRsi() <= 30 && isBuy)
      reasons.add(String.format("RSI sobrevendido (%.1f)", ind.getRsi()));
    if (ind.getRsi() >= 70 && !isBuy)
      reasons.add(String.format("RSI sobrecomprado (%.1f)", ind.getRsi()));

    if (ind.getTrend() == TradingEnums.Trend.STRONG_UPTREND && isBuy)
      reasons.add("Tendência de alta forte alinhada");
    if (ind.getTrend() == TradingEnums.Trend.STRONG_DOWNTREND && !isBuy)
      reasons.add("Tendência de baixa forte alinhada");

    if (ind.isHighVolume())
      reasons.add(String.format("Volume %.1fx acima da média", ind.getVolumeRatio()));

    if (reasons.isEmpty()) {
      reasons.add(isBuy
          ? "Confluência de indicadores favorece compra"
          : "Confluência de indicadores favorece venda");
    }

    return String.format("#%d: %s", rank, String.join(" + ", reasons));
  }

  // ─── Scores Individuais para Transparência ────────────────────────────────

  public double[] getIndividualScores(IndicatorResult ind, boolean forBuy) {
    return new double[]{
        forBuy ? calcRsiScoreBuy(ind.getRsi())    : calcRsiScoreSell(ind.getRsi()),
        forBuy ? calcMacdScoreBuy(ind)             : calcMacdScoreSell(ind),
        calcTrendScore(ind, forBuy),
        calcVolumeScore(ind.getVolumeRatio()),
        calcMomentumScore(ind.getPriceChangePercent24h(), forBuy)
    };
  }
}