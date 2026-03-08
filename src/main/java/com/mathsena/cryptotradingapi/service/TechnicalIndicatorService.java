package com.mathsena.cryptotradingapi.service;

import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serviço responsável pelo cálculo de todos os indicadores técnicos:
 * RSI, MACD, EMA (9, 21, 50) e análise de volume.
 */
@Service
public class TechnicalIndicatorService {

  private static final Logger log = LoggerFactory.getLogger(TechnicalIndicatorService.class);

  @Value("${indicator.rsi.period:14}")
  private int rsiPeriod;

  @Value("${indicator.rsi.overbought:70}")
  private double rsiOverbought;

  @Value("${indicator.rsi.oversold:30}")
  private double rsiOversold;

  @Value("${indicator.macd.fast-period:12}")
  private int macdFastPeriod;

  @Value("${indicator.macd.slow-period:26}")
  private int macdSlowPeriod;

  @Value("${indicator.macd.signal-period:9}")
  private int macdSignalPeriod;

  /**
   * Calcula todos os indicadores técnicos a partir de uma lista de candles.
   *
   * @param candles Lista de candles em ordem cronológica (do mais antigo ao mais recente)
   * @return IndicatorResult com todos os valores calculados
   */
  public IndicatorResult calculate(List<Candlestick> candles) {
    if (candles == null || candles.size() < macdSlowPeriod + macdSignalPeriod + 5) {
      throw new IllegalArgumentException(
          "Candles insuficientes para cálculo. Mínimo necessário: "
              + (macdSlowPeriod + macdSignalPeriod + 5));
    }

    double[] closes = candles.stream()
        .mapToDouble(Candlestick::getCloseDouble)
        .toArray();

    double[] volumes = candles.stream()
        .mapToDouble(Candlestick::getVolumeDouble)
        .toArray();

    Candlestick last = candles.get(candles.size() - 1);
    Candlestick first = candles.get(0);

    // ─── Calcular Indicadores ─────────────────────────────────────────────
    double rsi = calculateRSI(closes, rsiPeriod);
    double[] macd = calculateMACD(closes);
    double[] emas = calculateEMAs(closes);
    double[] volumeStats = calculateVolumeStats(volumes);

    // ─── MACD Crossover ───────────────────────────────────────────────────
    double[] macdPrev = calculateMACDPrevious(closes);
    String macdCrossover = detectMACDCrossover(macdPrev, macd);

    // ─── RSI Signal ───────────────────────────────────────────────────────
    String rsiSignal = getRsiSignal(rsi);

    // ─── Variação de Preço 24h ────────────────────────────────────────────
    double priceChange = last.getCloseDouble() - first.getCloseDouble();
    double priceChangePercent = (priceChange / first.getCloseDouble()) * 100;

    // ─── Tendência Geral ──────────────────────────────────────────────────
    int bullScore = 0;
    int bearScore = 0;

    // RSI
    if (rsi > 50) bullScore += 15; else bearScore += 15;
    if (rsi < rsiOversold) bullScore += 20;
    if (rsi > rsiOverbought) bearScore += 20;

    // MACD
    if (macd[0] > macd[1]) bullScore += 20; else bearScore += 20;
    if (macd[0] > 0) bullScore += 10; else bearScore += 10;
    if ("BULLISH_CROSS".equals(macdCrossover)) bullScore += 15;
    if ("BEARISH_CROSS".equals(macdCrossover)) bearScore += 15;

    // EMA alignment
    double price = last.getCloseDouble();
    if (price > emas[0] && emas[0] > emas[1] && emas[1] > emas[2]) bullScore += 20;
    else if (price < emas[0] && emas[0] < emas[1] && emas[1] < emas[2]) bearScore += 20;

    TradingEnums.Trend trend = determineTrend(bullScore, bearScore);
    String emaTrend = determineEmaTrend(price, emas[0], emas[1], emas[2]);

    log.debug("Indicadores calculados para {} candles | RSI={} | MACD={}/{} | Score Touro={} Urso={}",
        candles.size(), String.format("%.2f", rsi),
        String.format("%.4f", macd[0]), String.format("%.4f", macd[1]),
        bullScore, bearScore);

    return IndicatorResult.builder()
        .symbol(last.getSymbol())
        .interval(last.getInterval())
        .currentPrice(price)
        .rsi(rsi)
        .rsiSignal(rsiSignal)
        .macdLine(macd[0])
        .macdSignalLine(macd[1])
        .macdHistogram(macd[2])
        .macdCrossover(macdCrossover)
        .macdAboveZero(macd[0] > 0)
        .ema9(emas[0])
        .ema21(emas[1])
        .ema50(emas[2])
        .emaTrend(emaTrend)
        .currentVolume(volumes[volumes.length - 1])
        .averageVolume(volumeStats[0])
        .volumeRatio(volumeStats[1])
        .highVolume(volumeStats[1] > 1.5)
        .priceChange24h(priceChange)
        .priceChangePercent24h(priceChangePercent)
        .trend(trend)
        .bullishScore(bullScore)
        .bearishScore(bearScore)
        .build();
  }

  // ─── RSI ──────────────────────────────────────────────────────────────────

  /**
   * Calcula RSI usando o método Wilder (EMA suavizada).
   */
  public double calculateRSI(double[] closes, int period) {
    if (closes.length < period + 1) {
      throw new IllegalArgumentException("Dados insuficientes para RSI com período " + period);
    }

    double gainSum = 0.0;
    double lossSum = 0.0;

    // Primeira média simples
    for (int i = 1; i <= period; i++) {
      double change = closes[i] - closes[i - 1];
      if (change > 0) gainSum += change;
      else lossSum += Math.abs(change);
    }

    double avgGain = gainSum / period;
    double avgLoss = lossSum / period;

    // Suavização de Wilder
    for (int i = period + 1; i < closes.length; i++) {
      double change = closes[i] - closes[i - 1];
      double gain = change > 0 ? change : 0;
      double loss = change < 0 ? Math.abs(change) : 0;

      avgGain = (avgGain * (period - 1) + gain) / period;
      avgLoss = (avgLoss * (period - 1) + loss) / period;
    }

    if (avgLoss == 0.0) return 100.0;
    double rs = avgGain / avgLoss;
    return 100.0 - (100.0 / (1.0 + rs));
  }

  // ─── MACD ─────────────────────────────────────────────────────────────────

  /**
   * Calcula MACD (linha, sinal, histograma) para o ponto mais recente.
   * Retorna: [macdLine, signalLine, histogram]
   */
  public double[] calculateMACD(double[] closes) {
    double[] emaFast = calculateEMAArray(closes, macdFastPeriod);
    double[] emaSlow = calculateEMAArray(closes, macdSlowPeriod);

    int len = Math.min(emaFast.length, emaSlow.length);
    double[] macdLine = new double[len];

    int slowOffset = closes.length - emaSlow.length;
    int fastOffset = closes.length - emaFast.length;

    for (int i = 0; i < len; i++) {
      macdLine[i] = emaFast[emaFast.length - len + i] - emaSlow[emaSlow.length - len + i];
    }

    double[] signalLine = calculateEMAArray(macdLine, macdSignalPeriod);
    double lastMacd = macdLine[macdLine.length - 1];
    double lastSignal = signalLine[signalLine.length - 1];

    return new double[]{lastMacd, lastSignal, lastMacd - lastSignal};
  }

  private double[] calculateMACDPrevious(double[] closes) {
    if (closes.length < 2) return new double[]{0, 0, 0};
    double[] sub = new double[closes.length - 1];
    System.arraycopy(closes, 0, sub, 0, closes.length - 1);
    try {
      return calculateMACD(sub);
    } catch (Exception e) {
      return new double[]{0, 0, 0};
    }
  }

  // ─── EMA ──────────────────────────────────────────────────────────────────

  /**
   * Retorna as EMAs para os períodos 9, 21 e 50 no ponto mais recente.
   */
  public double[] calculateEMAs(double[] closes) {
    double ema9 = calculateEMA(closes, 9);
    double ema21 = calculateEMA(closes, 21);
    double ema50 = closes.length >= 50 ? calculateEMA(closes, 50) : ema21;
    return new double[]{ema9, ema21, ema50};
  }

  public double calculateEMA(double[] closes, int period) {
    double[] emaArray = calculateEMAArray(closes, period);
    return emaArray[emaArray.length - 1];
  }

  /**
   * Calcula EMA completa como array para uso no MACD.
   */
  public double[] calculateEMAArray(double[] closes, int period) {
    if (closes.length < period) {
      throw new IllegalArgumentException("Dados insuficientes para EMA(" + period + ")");
    }

    double[] ema = new double[closes.length - period + 1];
    double multiplier = 2.0 / (period + 1.0);

    // Primeira EMA é a SMA
    double sum = 0;
    for (int i = 0; i < period; i++) sum += closes[i];
    ema[0] = sum / period;

    for (int i = 1; i < ema.length; i++) {
      ema[i] = (closes[period - 1 + i] - ema[i - 1]) * multiplier + ema[i - 1];
    }

    return ema;
  }

  // ─── Volume ───────────────────────────────────────────────────────────────

  private double[] calculateVolumeStats(double[] volumes) {
    int lookback = Math.min(20, volumes.length - 1);
    double sum = 0;
    for (int i = volumes.length - 1 - lookback; i < volumes.length - 1; i++) {
      sum += volumes[i];
    }
    double avgVolume = sum / lookback;
    double currentVolume = volumes[volumes.length - 1];
    double ratio = avgVolume > 0 ? currentVolume / avgVolume : 1.0;
    return new double[]{avgVolume, ratio};
  }

  // ─── Crossover MACD ───────────────────────────────────────────────────────

  private String detectMACDCrossover(double[] prev, double[] curr) {
    if (prev[0] < prev[1] && curr[0] > curr[1]) return "BULLISH_CROSS";
    if (prev[0] > prev[1] && curr[0] < curr[1]) return "BEARISH_CROSS";
    return "NONE";
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private String getRsiSignal(double rsi) {
    if (rsi >= rsiOverbought) return "Sobrecomprado";
    if (rsi <= rsiOversold) return "Sobrevendido";
    if (rsi >= 60) return "Força Alta";
    if (rsi <= 40) return "Fraqueza Baixa";
    return "Neutro";
  }

  private TradingEnums.Trend determineTrend(int bullScore, int bearScore) {
    int diff = bullScore - bearScore;
    if (diff >= 40) return TradingEnums.Trend.STRONG_UPTREND;
    if (diff >= 15) return TradingEnums.Trend.UPTREND;
    if (diff <= -40) return TradingEnums.Trend.STRONG_DOWNTREND;
    if (diff <= -15) return TradingEnums.Trend.DOWNTREND;
    return TradingEnums.Trend.SIDEWAYS;
  }

  private String determineEmaTrend(double price, double ema9, double ema21, double ema50) {
    if (price > ema9 && ema9 > ema21 && ema21 > ema50)
      return "Alta alinhada: Preço > EMA9 > EMA21 > EMA50";
    if (price < ema9 && ema9 < ema21 && ema21 < ema50)
      return "Baixa alinhada: Preço < EMA9 < EMA21 < EMA50";
    if (price > ema21)
      return "Tendência de alta parcial";
    if (price < ema21)
      return "Tendência de baixa parcial";
    return "Sem tendência clara";
  }
}
