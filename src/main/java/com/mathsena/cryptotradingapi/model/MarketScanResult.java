package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Resultado completo de um scan de mercado.
 * Contém os top 5 ativos para compra e top 5 para venda,
 * além de metadados da análise.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketScanResult {

  private Instant scannedAt;
  private String exchange;
  private String interval;

  // ─── Totais ───────────────────────────────────────────────────────────────
  private int totalSymbolsScanned;
  private int totalAnalyzed;           // Que passaram nos filtros de liquidez
  private int totalBuySignals;
  private int totalSellSignals;
  private int totalNeutral;
  private long scanDurationMs;

  // ─── Rankings ─────────────────────────────────────────────────────────────
  private List<RankedAsset> top5Buy;   // Top 5 melhores para COMPRA
  private List<RankedAsset> top5Sell;  // Top 5 melhores para VENDA

  // ─── Contexto de Mercado ──────────────────────────────────────────────────
  private String marketSentiment;      // "BULLISH", "BEARISH", "NEUTRAL"
  private String marketSentimentLabel;
  private double dominanceBullPercent; // % de ativos com sinal de alta
  private double dominanceBearPercent;

  // ─── Filtros Utilizados ───────────────────────────────────────────────────
  private double minVolume24hUSDT;     // Volume mínimo filtrado
  private int topNSymbols;             // Quantos símbolos do universo foram usados
}