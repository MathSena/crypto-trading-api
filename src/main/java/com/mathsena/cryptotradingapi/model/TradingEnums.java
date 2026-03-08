package com.mathsena.cryptotradingapi.model;

public class TradingEnums {

  public enum SignalType {
    STRONG_BUY("Compra Forte 🟢🟢"),
    BUY("Compra 🟢"),
    NEUTRAL("Neutro ⚪"),
    SELL("Venda 🔴"),
    STRONG_SELL("Venda Forte 🔴🔴");

    private final String label;

    SignalType(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }

  public enum Trend {
    STRONG_UPTREND("Tendência de Alta Forte ↑↑"),
    UPTREND("Tendência de Alta ↑"),
    SIDEWAYS("Lateral →"),
    DOWNTREND("Tendência de Baixa ↓"),
    STRONG_DOWNTREND("Tendência de Baixa Forte ↓↓");

    private final String label;

    Trend(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }

  public enum Exchange {
    BINANCE, BYBIT
  }

  public enum Timeframe {
    M1("1m"), M3("3m"), M5("5m"), M15("15m"),
    M30("30m"), H1("1h"), H2("2h"), H4("4h"),
    H6("6h"), H8("8h"), H12("12h"), D1("1d"),
    W1("1w"), MN("1M");

    private final String value;

    Timeframe(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public enum RiskLevel {
    LOW("Baixo"), MEDIUM("Médio"), HIGH("Alto"), EXTREME("Extremo");

    private final String label;

    RiskLevel(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }
}