package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Sinal MTF (Multi-Time Frame) gerado pelas estratégias Sniper Scalp e Trend Rider.
 *
 * Lógica de dois timeframes:
 *  - 1h: "O Chefe" — define a permissão para entrar
 *  - 3m: "O Gatilho" — define o momento exato da entrada
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MtfSignal {

  private String symbol;
  private String exchange;
  private Instant generatedAt;

  // ─── Estratégia ────────────────────────────────────────────────────────────
  private TradingEnums.StrategyType strategyType;
  private String strategyLabel;
  private String strategyDescription;

  // ─── Status ────────────────────────────────────────────────────────────────
  private TradingEnums.StrategyStatus status;
  private String statusReason;

  // ─── Contexto 1h (O Chefe) ─────────────────────────────────────────────────
  private TimeframeContext context1h;

  // ─── Contexto 3m (O Gatilho) ───────────────────────────────────────────────
  private TimeframeContext context3m;

  // ─── Permissão do 1h ───────────────────────────────────────────────────────
  private boolean permissionGranted;
  private String permissionReason;

  // ─── Gatilho do 3m ─────────────────────────────────────────────────────────
  private boolean triggerActive;
  private String triggerReason;

  // ─── Travas de Segurança ───────────────────────────────────────────────────
  private boolean exhaustionBlock;        // RSI 3m > 75 → bloqueia compra
  private boolean macdConfirmationMissing; // MACD 3m ainda não cruzou
  private String macroEvaluation;         // BULLISH, BEARISH, NEUTRAL (sentimento 1h)
  private String macroAdvice;             // Conselho com base no macro

  // ─── Entrada e Gestão de Risco (preenchido se status = ACTIVE) ─────────────
  private double entryPrice;
  private double entryZoneLow;
  private double entryZoneHigh;
  private double stopLoss;
  private double stopLossPercent;
  private String stopLossRationale;
  private double takeProfit1;
  private double takeProfit2;
  private double takeProfit3;
  private String tp1Label;
  private String tp2Label;
  private String tp3Label;
  private double riskRewardRatio;
  private double suggestedLeverage;
  private TradingEnums.RiskLevel riskLevel;
  private String riskLevelLabel;

  // ─── Alertas ───────────────────────────────────────────────────────────────
  private List<String> alerts;
  private List<String> waitingConditions;  // o que falta para ativar o sinal

  // ─── Previsão de Duração ───────────────────────────────────────────────────
  private String estimatedDuration;
  private String durationBasis;

  // ─── Sub-modelo: Contexto de um Timeframe ──────────────────────────────────
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TimeframeContext {
    private String interval;
    private double currentPrice;
    private double rsi;
    private String rsiSignal;
    private String macdCrossover;
    private double macdLine;
    private double macdSignalLine;
    private double macdHistogram;
    private boolean macdAboveZero;
    private TradingEnums.Trend trend;
    private String trendLabel;
    private double ema9;
    private double ema21;
    private double ema50;
    private String emaTrend;
    private double volumeRatio;
    private boolean highVolume;
  }
}
