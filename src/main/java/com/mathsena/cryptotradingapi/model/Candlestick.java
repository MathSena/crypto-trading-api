package com.mathsena.cryptotradingapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representa um candle OHLCV (Open, High, Low, Close, Volume)
 * de um par de trading em um determinado timeframe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candlestick {

  private String symbol;
  private String interval;

  private Instant openTime;
  private Instant closeTime;

  private BigDecimal open;
  private BigDecimal high;
  private BigDecimal low;
  private BigDecimal close;
  private BigDecimal volume;
  private BigDecimal quoteVolume;

  private long numberOfTrades;
  private boolean isClosed;

  // ─── Helpers ──────────────────────────────────────────────────────────────

  public double getCloseDouble() {
    return close != null ? close.doubleValue() : 0.0;
  }

  public double getHighDouble() {
    return high != null ? high.doubleValue() : 0.0;
  }

  public double getLowDouble() {
    return low != null ? low.doubleValue() : 0.0;
  }

  public double getVolumeDouble() {
    return volume != null ? volume.doubleValue() : 0.0;
  }

  public boolean isBullish() {
    return close != null && open != null && close.compareTo(open) > 0;
  }

  public boolean isBearish() {
    return close != null && open != null && close.compareTo(open) < 0;
  }
}