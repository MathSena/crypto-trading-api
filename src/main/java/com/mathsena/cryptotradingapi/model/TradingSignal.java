package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Sinal completo de trading gerado pelo sistema.
 * Inclui entrada, Stop Loss, Take Profit, alertas e previsão de tempo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingSignal {

  private String symbol;
  private String exchange;
  private String interval;
  private Instant generatedAt;

  // ─── Sinal Principal ───────────────────────────────────────────────────────
  private TradingEnums.SignalType signalType;
  private double signalStrength;        // 0.0 a 1.0 (confiança do sinal)
  private String signalDescription;

  // ─── Preço de Entrada ──────────────────────────────────────────────────────
  private double entryPrice;
  private double entryZoneLow;
  private double entryZoneHigh;

  // ─── Stop Loss ─────────────────────────────────────────────────────────────
  private double stopLoss;
  private double stopLossPercent;
  private String stopLossRationale;

  // ─── Take Profit (múltiplos alvos) ────────────────────────────────────────
  private double takeProfit1;           // TP1 — conservador
  private double takeProfit2;           // TP2 — principal
  private double takeProfit3;           // TP3 — agressivo
  private double riskRewardRatio;       // R:R calculado com TP2

  // ─── Gestão de Risco ──────────────────────────────────────────────────────
  private TradingEnums.RiskLevel riskLevel;
  private double suggestedLeverage;     // Alavancagem recomendada
  private double positionSizePercent;   // % do capital a usar

  // ─── Alertas e Avisos ─────────────────────────────────────────────────────
  private List<String> alerts;          // Alertas importantes
  private List<String> exitWarnings;    // Quando sair da posição
  private List<String> targetAdjustments; // Quando ajustar alvos
  private boolean shouldExit;           // Saída imediata recomendada?
  private String exitReason;

  // ─── Previsão de Tempo ─────────────────────────────────────────────────────
  private String estimatedDuration;     // Ex: "4h – 12h"
  private String estimatedDurationMin;  // Ex: "4h"
  private String estimatedDurationMax;  // Ex: "12h"
  private String timePredictionBasis;   // Explicação da previsão

  // ─── Contexto de Mercado ──────────────────────────────────────────────────
  private TradingEnums.Trend trend;
  private IndicatorResult indicators;

  // ─── Helpers ───────────────────────────────────────────────────────────────
  public boolean isBuySignal() {
    return signalType == TradingEnums.SignalType.BUY ||
        signalType == TradingEnums.SignalType.STRONG_BUY;
  }

  public boolean isSellSignal() {
    return signalType == TradingEnums.SignalType.SELL ||
        signalType == TradingEnums.SignalType.STRONG_SELL;
  }

  public String getSignalLabel() {
    return signalType != null ? signalType.getLabel() : "N/A";
  }
}