package com.mathsena.cryptotradingapi.dto;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

// ─── Request DTO ──────────────────────────────────────────────────────────────
class SignalRequestDTO {

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SignalRequest {

    @NotBlank(message = "Symbol é obrigatório. Ex: BTCUSDT")
    @Pattern(regexp = "^[A-Z]{2,10}(USDT|BUSD|BTC|ETH|BNB)$",
        message = "Symbol inválido. Use formato: BTCUSDT")
    private String symbol;

    @Builder.Default
    private String interval = "1h";

    @Builder.Default
    private TradingEnums.Exchange exchange = TradingEnums.Exchange.BINANCE;

    private double accountBalance;
    private double riskPercent;
  }
}

// ─── Response DTOs ────────────────────────────────────────────────────────────

class SignalResponseDTO {

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private long timestamp;

    public static <T> ApiResponse<T> ok(T data) {
      return ApiResponse.<T>builder()
          .success(true)
          .message("OK")
          .data(data)
          .timestamp(System.currentTimeMillis())
          .build();
    }

    public static <T> ApiResponse<T> error(String message) {
      return ApiResponse.<T>builder()
          .success(false)
          .message(message)
          .timestamp(System.currentTimeMillis())
          .build();
    }
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SignalSummary {
    private String symbol;
    private String exchange;
    private String interval;
    private String signal;
    private double signalStrength;
    private double currentPrice;
    private double stopLoss;
    private double takeProfit1;
    private double takeProfit2;
    private double takeProfit3;
    private double riskRewardRatio;
    private String trend;
    private double rsi;
    private String macdCrossover;
    private boolean shouldExit;
    private List<String> topAlerts;
    private String estimatedDuration;
  }
}