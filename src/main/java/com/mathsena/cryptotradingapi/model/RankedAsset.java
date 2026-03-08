package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Representa um ativo ranqueado após análise do scanner de mercado.
 * Contém score, sinal, indicadores e dados de gestão de risco.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedAsset {

  private int rank;                         // Posição no ranking (1 = melhor)
  private String symbol;
  private String exchange;
  private String interval;

  // ─── Score ────────────────────────────────────────────────────────────────
  private double score;                     // 0.0 a 100.0 (score composto)
  private String scoreGrade;               // A+, A, B+, B, C, D

  // ─── Sinal ────────────────────────────────────────────────────────────────
  private TradingEnums.SignalType signalType;
  private double signalStrength;
  private String signalLabel;

  // ─── Preço e Volume ───────────────────────────────────────────────────────
  private double currentPrice;
  private double priceChangePercent24h;
  private double volume24hUSDT;            // Volume em USDT nas últimas 24h
  private double volumeRatio;              // Volume atual vs média

  // ─── Indicadores-chave ────────────────────────────────────────────────────
  private double rsi;
  private String rsiSignal;
  private double macdHistogram;
  private String macdCrossover;
  private TradingEnums.Trend trend;

  // ─── Gestão de Risco ──────────────────────────────────────────────────────
  private double entryPrice;
  private double stopLoss;
  private double stopLossPercent;
  private double takeProfit1;
  private double takeProfit2;
  private double takeProfit3;
  private double riskRewardRatio;
  private double suggestedLeverage;
  private TradingEnums.RiskLevel riskLevel;

  // ─── Alertas ──────────────────────────────────────────────────────────────
  private List<String> topAlerts;          // Até 3 alertas principais
  private String estimatedDuration;

  // ─── Razão do Ranking ─────────────────────────────────────────────────────
  private String rankingReason;            // Explicação do porque foi ranqueado aqui

  // ─── Scores individuais (transparência) ───────────────────────────────────
  private double rsiScore;
  private double macdScore;
  private double trendScore;
  private double volumeScore;
  private double momentumScore;
}