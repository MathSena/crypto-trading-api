package com.mathsena.cryptotradingapi.service;


import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Serviço principal de geração de sinais de trading.
 * Combina indicadores técnicos com gestão de risco para gerar
 * sinais de compra/venda com SL, TP, alertas e previsão de tempo.
 */
@Service
public class SignalGeneratorService {

  private static final Logger log = LoggerFactory.getLogger(SignalGeneratorService.class);

  private final TechnicalIndicatorService indicatorService;
  private final RiskManagementService riskService;

  public SignalGeneratorService(TechnicalIndicatorService indicatorService,
      RiskManagementService riskService) {
    this.indicatorService = indicatorService;
    this.riskService = riskService;
  }

  /**
   * Gera um sinal de trading completo a partir de candles.
   *
   * @param candles        Lista de candles OHLCV (do mais antigo ao mais recente)
   * @param exchange       Exchange de origem (BINANCE ou BYBIT)
   * @param accountBalance Saldo da conta (opcional, para cálculo de posição)
   * @param riskPercent    % de risco por trade (opcional)
   */
  public TradingSignal generate(List<Candlestick> candles,
      TradingEnums.Exchange exchange,
      double accountBalance,
      double riskPercent) {

    // 1. Calcular todos os indicadores
    IndicatorResult indicators = indicatorService.calculate(candles);
    log.info("Gerando sinal para {} | RSI={} | MACD={}/{} | Tendência={}",
        indicators.getSymbol(),
        String.format("%.2f", indicators.getRsi()),
        String.format("%.4f", indicators.getMacdLine()),
        String.format("%.4f", indicators.getMacdSignalLine()),
        indicators.getTrend().name());

    // 2. Determinar tipo de sinal
    TradingEnums.SignalType signalType = determineSignalType(indicators);
    double signalStrength = calculateSignalStrength(indicators, signalType);

    boolean isBuy = signalType == TradingEnums.SignalType.BUY
        || signalType == TradingEnums.SignalType.STRONG_BUY;

    // 3. Preço de entrada
    double entryPrice = indicators.getCurrentPrice();
    double entryZoneLow = entryPrice * (isBuy ? 0.999 : 1.001);
    double entryZoneHigh = entryPrice * (isBuy ? 1.001 : 0.999);

    // 4. Mínimo e máximo recentes (últimas 20 candles)
    int lookback = Math.min(20, candles.size());
    List<Candlestick> recentCandles = candles.subList(candles.size() - lookback, candles.size());
    double recentLow  = recentCandles.stream().mapToDouble(Candlestick::getLowDouble).min().orElse(entryPrice * 0.98);
    double recentHigh = recentCandles.stream().mapToDouble(Candlestick::getHighDouble).max().orElse(entryPrice * 1.02);

    // 5. Stop Loss
    double stopLoss = riskService.calculateStopLoss(entryPrice, isBuy, indicators, recentLow, recentHigh);
    double slPercent = Math.abs(entryPrice - stopLoss) / entryPrice * 100;
    String slRationale = riskService.getStopLossRationale(entryPrice, stopLoss, isBuy, indicators);

    // 6. Take Profits
    double[] tps = riskService.calculateTakeProfits(entryPrice, stopLoss, isBuy);
    double rrRatio = Math.abs(tps[1] - entryPrice) / Math.abs(entryPrice - stopLoss);

    // 7. Gestão de risco
    double leverage = riskService.suggestLeverage(indicators, slPercent);
    double positionSize = riskService.calculatePositionSize(entryPrice, stopLoss, accountBalance, riskPercent);
    TradingEnums.RiskLevel riskLevel = riskService.assessRiskLevel(indicators, signalStrength);

    // 8. Alertas e avisos
    List<String> alerts = generateAlerts(indicators, signalType, signalStrength);
    List<String> exitWarnings = riskService.generateExitWarnings(indicators, isBuy);
    List<String> targetAdjustments = riskService.generateTargetAdjustments(
        entryPrice, entryPrice, tps[0], tps[1], isBuy);
    boolean shouldExit = riskService.shouldExitNow(indicators, isBuy);
    String exitReason = riskService.getExitReason(indicators, isBuy);

    // 9. Previsão de tempo
    String[] timePrediction = predictDuration(indicators, candles.get(0).getInterval());

    // 10. Descrição do sinal
    String signalDescription = buildSignalDescription(signalType, indicators, signalStrength);

    return TradingSignal.builder()
        .symbol(indicators.getSymbol())
        .exchange(exchange.name())
        .interval(indicators.getInterval())
        .generatedAt(Instant.now())
        .signalType(signalType)
        .signalStrength(signalStrength)
        .signalDescription(signalDescription)
        .entryPrice(entryPrice)
        .entryZoneLow(Math.round(entryZoneLow * 100.0) / 100.0)
        .entryZoneHigh(Math.round(entryZoneHigh * 100.0) / 100.0)
        .stopLoss(stopLoss)
        .stopLossPercent(Math.round(slPercent * 100.0) / 100.0)
        .stopLossRationale(slRationale)
        .takeProfit1(tps[0])
        .takeProfit2(tps[1])
        .takeProfit3(tps[2])
        .riskRewardRatio(Math.round(rrRatio * 100.0) / 100.0)
        .riskLevel(riskLevel)
        .suggestedLeverage(leverage)
        .positionSizePercent(positionSize)
        .alerts(alerts)
        .exitWarnings(exitWarnings)
        .targetAdjustments(targetAdjustments)
        .shouldExit(shouldExit)
        .exitReason(exitReason)
        .estimatedDuration(timePrediction[0])
        .estimatedDurationMin(timePrediction[1])
        .estimatedDurationMax(timePrediction[2])
        .timePredictionBasis(timePrediction[3])
        .trend(indicators.getTrend())
        .indicators(indicators)
        .build();
  }

  // ─── Determinação do Sinal ────────────────────────────────────────────────

  private TradingEnums.SignalType determineSignalType(IndicatorResult ind) {
    int bullPoints = 0;
    int bearPoints = 0;

    // RSI
    if (ind.getRsi() < 30) bullPoints += 3;       // sobrevenda forte
    else if (ind.getRsi() < 45) bullPoints += 1;
    else if (ind.getRsi() > 70) bearPoints += 3;  // sobrecompra forte
    else if (ind.getRsi() > 55) bearPoints += 1;

    // MACD crossover (peso maior)
    if ("BULLISH_CROSS".equals(ind.getMacdCrossover())) bullPoints += 4;
    else if ("BEARISH_CROSS".equals(ind.getMacdCrossover())) bearPoints += 4;

    // MACD acima/abaixo de zero
    if (ind.isMacdAboveZero()) bullPoints += 2;
    else bearPoints += 2;

    // MACD linha vs sinal
    if (ind.getMacdLine() > ind.getMacdSignalLine()) bullPoints += 2;
    else bearPoints += 2;

    // EMA alignment
    double price = ind.getCurrentPrice();
    if (price > ind.getEma9() && ind.getEma9() > ind.getEma21()) bullPoints += 2;
    else if (price < ind.getEma9() && ind.getEma9() < ind.getEma21()) bearPoints += 2;

    // Tendência geral
    if (ind.getTrend() == TradingEnums.Trend.STRONG_UPTREND) bullPoints += 3;
    else if (ind.getTrend() == TradingEnums.Trend.UPTREND) bullPoints += 1;
    else if (ind.getTrend() == TradingEnums.Trend.STRONG_DOWNTREND) bearPoints += 3;
    else if (ind.getTrend() == TradingEnums.Trend.DOWNTREND) bearPoints += 1;

    // Volume confirma
    if (ind.isHighVolume()) {
      if (bullPoints > bearPoints) bullPoints += 1;
      else bearPoints += 1;
    }

    int diff = bullPoints - bearPoints;
    if (diff >= 8) return TradingEnums.SignalType.STRONG_BUY;
    if (diff >= 4) return TradingEnums.SignalType.BUY;
    if (diff <= -8) return TradingEnums.SignalType.STRONG_SELL;
    if (diff <= -4) return TradingEnums.SignalType.SELL;
    return TradingEnums.SignalType.NEUTRAL;
  }

  private double calculateSignalStrength(IndicatorResult ind, TradingEnums.SignalType type) {
    if (type == TradingEnums.SignalType.NEUTRAL) return 0.3;

    double strength = 0.4;

    boolean isBull = type == TradingEnums.SignalType.BUY || type == TradingEnums.SignalType.STRONG_BUY;

    if ("BULLISH_CROSS".equals(ind.getMacdCrossover()) && isBull) strength += 0.25;
    if ("BEARISH_CROSS".equals(ind.getMacdCrossover()) && !isBull) strength += 0.25;
    if (ind.isHighVolume()) strength += 0.15;
    if (isBull && ind.getRsi() < 40) strength += 0.10;
    if (!isBull && ind.getRsi() > 60) strength += 0.10;
    if (type == TradingEnums.SignalType.STRONG_BUY || type == TradingEnums.SignalType.STRONG_SELL)
      strength += 0.10;

    return Math.min(Math.round(strength * 100.0) / 100.0, 1.0);
  }

  // ─── Alertas ──────────────────────────────────────────────────────────────

  private List<String> generateAlerts(IndicatorResult ind,
      TradingEnums.SignalType signalType,
      double strength) {
    List<String> alerts = new ArrayList<>();

    if ("BULLISH_CROSS".equals(ind.getMacdCrossover()))
      alerts.add("🚨 MACD Bullish Cross detectado — forte sinal de compra!");
    if ("BEARISH_CROSS".equals(ind.getMacdCrossover()))
      alerts.add("🚨 MACD Bearish Cross detectado — forte sinal de venda!");

    if (ind.getRsi() < 25)
      alerts.add("🔔 RSI (" + String.format("%.1f", ind.getRsi()) + ") em sobrevenda extrema — alta probabilidade de repique.");
    else if (ind.getRsi() > 75)
      alerts.add("🔔 RSI (" + String.format("%.1f", ind.getRsi()) + ") em sobrecompra extrema — cuidado com reversão.");

    if (ind.isHighVolume())
      alerts.add("📊 Volume " + String.format("%.1fx", ind.getVolumeRatio()) + " acima da média — movimento com convicção.");

    if (ind.getTrend() == TradingEnums.Trend.STRONG_UPTREND && signalType == TradingEnums.SignalType.STRONG_BUY)
      alerts.add("💚 Confluência: Tendência forte de alta + Sinal de compra forte.");

    if (ind.getTrend() == TradingEnums.Trend.STRONG_DOWNTREND && signalType == TradingEnums.SignalType.STRONG_SELL)
      alerts.add("🔴 Confluência: Tendência forte de baixa + Sinal de venda forte.");

    if (ind.getTrend() == TradingEnums.Trend.SIDEWAYS)
      alerts.add("⚠️ Mercado lateral — sinais menos confiáveis. Considere aguardar rompimento.");

    if (strength < 0.5)
      alerts.add("⚠️ Sinal fraco (" + String.format("%.0f%%", strength * 100) + " de confiança). Aguarde confirmação.");

    return alerts;
  }

  // ─── Previsão de Tempo ────────────────────────────────────────────────────

  /**
   * Estima quanto tempo a trade pode levar para atingir o alvo (TP2).
   * Baseia-se no timeframe, força da tendência e volatilidade.
   */
  private String[] predictDuration(IndicatorResult ind, String interval) {
    // Duração base por candle em horas
    double candleHours = switch (interval) {
      case "1m" -> 1.0 / 60;
      case "3m" -> 3.0 / 60;
      case "5m" -> 5.0 / 60;
      case "15m" -> 15.0 / 60;
      case "30m" -> 0.5;
      case "1h" -> 1.0;
      case "2h" -> 2.0;
      case "4h" -> 4.0;
      case "6h" -> 6.0;
      case "8h" -> 8.0;
      case "12h" -> 12.0;
      case "1d" -> 24.0;
      case "1w" -> 168.0;
      default -> 1.0;
    };

    // Número de candles estimados para atingir TP2 baseado na força
    double minCandles, maxCandles;

    if (ind.getTrend() == TradingEnums.Trend.STRONG_UPTREND ||
        ind.getTrend() == TradingEnums.Trend.STRONG_DOWNTREND) {
      minCandles = 3; maxCandles = 8;
    } else if (ind.getTrend() == TradingEnums.Trend.UPTREND ||
        ind.getTrend() == TradingEnums.Trend.DOWNTREND) {
      minCandles = 5; maxCandles = 15;
    } else {
      minCandles = 8; maxCandles = 30; // Lateral é imprevisível
    }

    // Volume alto = movimento mais rápido
    if (ind.isHighVolume()) {
      minCandles *= 0.7;
      maxCandles *= 0.7;
    }

    double minHours = minCandles * candleHours;
    double maxHours = maxCandles * candleHours;

    String minStr = formatDuration(minHours);
    String maxStr = formatDuration(maxHours);
    String rangeStr = minStr + " – " + maxStr;

    String basis = String.format(
        "Baseado no timeframe %s, tendência %s e volume %s (%.1fx média). " +
            "Estimativa de %d a %d candles até o TP2.",
        interval,
        ind.getTrend().getLabel(),
        ind.isHighVolume() ? "alto" : "normal",
        ind.getVolumeRatio(),
        (int) minCandles, (int) maxCandles
    );

    return new String[]{rangeStr, minStr, maxStr, basis};
  }

  private String formatDuration(double hours) {
    if (hours < 1) return (int) (hours * 60) + "min";
    if (hours < 24) return String.format("%.0fh", hours);
    if (hours < 168) return String.format("%.1fd", hours / 24);
    return String.format("%.1fw", hours / 168);
  }

  private String buildSignalDescription(TradingEnums.SignalType type,
      IndicatorResult ind, double strength) {
    String direction = switch (type) {
      case STRONG_BUY -> "Compra Forte";
      case BUY -> "Compra";
      case STRONG_SELL -> "Venda Forte";
      case SELL -> "Venda";
      default -> "Neutro";
    };

    return String.format(
        "%s com %.0f%% de confiança. RSI=%.1f (%s), MACD=%s, " +
            "Tendência: %s. Volume %.1fx da média.",
        direction,
        strength * 100,
        ind.getRsi(),
        ind.getRsiSignal(),
        ind.getMacdCrossover(),
        ind.getTrend().getLabel(),
        ind.getVolumeRatio()
    );
  }
}