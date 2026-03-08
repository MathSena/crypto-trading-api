package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado calculado de todos os indicadores técnicos para um ativo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorResult {

  private String symbol;
  private String interval;
  private double currentPrice;

  // ─── RSI ───────────────────────────────────────────────────────────────────
  private double rsi;
  private String rsiSignal;         // "Sobrecomprado", "Sobrevendido", "Neutro"

  // ─── MACD ──────────────────────────────────────────────────────────────────
  private double macdLine;
  private double macdSignalLine;
  private double macdHistogram;
  private String macdCrossover;     // "BULLISH_CROSS", "BEARISH_CROSS", "NONE"
  private boolean macdAboveZero;

  // ─── EMA ───────────────────────────────────────────────────────────────────
  private double ema9;
  private double ema21;
  private double ema50;
  private String emaTrend;

  // ─── Volume ────────────────────────────────────────────────────────────────
  private double currentVolume;
  private double averageVolume;
  private double volumeRatio;       // currentVolume / averageVolume
  private boolean highVolume;       // volumeRatio > 1.5

  // ─── Preço ─────────────────────────────────────────────────────────────────
  private double priceChange24h;
  private double priceChangePercent24h;

  // ─── Tendência Geral ───────────────────────────────────────────────────────
  private TradingEnums.Trend trend;
  private int bullishScore;         // 0–100
  private int bearishScore;         // 0–100
}