package com.mathsena.cryptotradingapi.service;

import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.MarketScanResult;
import com.mathsena.cryptotradingapi.model.RankedAsset;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Serviço principal do Scanner de Mercado.
 *
 * Fluxo de execução:
 * 1. Busca lista de todos os ativos USDT na exchange (Binance/Bybit API pública)
 * 2. Filtra por volume mínimo (liquidez)
 * 3. Analisa cada ativo em paralelo (thread pool)
 * 4. Calcula score composto (RSI 25% + MACD 30% + Trend 25% + Volume 10% + Momentum 10%)
 * 5. Ranqueia e retorna top 5 compra e top 5 venda
 */
@Service
public class MarketScannerService {

  private static final Logger log = LoggerFactory.getLogger(MarketScannerService.class);

  private static final double DEFAULT_MIN_VOLUME_USDT = 50_000_000;
  private static final int    DEFAULT_TOP_SYMBOLS     = 80;
  private static final int    TOP_N_RESULTS           = 5;
  private static final int    THREAD_POOL_SIZE        = 10;
  private static final int    CANDLES_FOR_SCAN        = 100;

  private final MarketDataService marketDataService;
  private final BinanceApiService binanceApiService;
  private final BybitApiService bybitApiService;
  private final TechnicalIndicatorService indicatorService;
  private final SignalGeneratorService signalGeneratorService;
  private final RiskManagementService riskManagementService;
  private final AssetScoringService scoringService;

  public MarketScannerService(MarketDataService marketDataService,
      BinanceApiService binanceApiService,
      BybitApiService bybitApiService,
      TechnicalIndicatorService indicatorService,
      SignalGeneratorService signalGeneratorService,
      RiskManagementService riskManagementService,
      AssetScoringService scoringService) {
    this.marketDataService = marketDataService;
    this.binanceApiService = binanceApiService;
    this.bybitApiService = bybitApiService;
    this.indicatorService = indicatorService;
    this.signalGeneratorService = signalGeneratorService;
    this.riskManagementService = riskManagementService;
    this.scoringService = scoringService;
  }

  // ─── Classe interna de item de scan (record) ──────────────────────────────
  // Campos acessados via métodos: item.ticker(), item.indicators(), item.signal(),
  // item.buyScore(), item.sellScore()
  private record ScanItem(
      MarketDataService.TickerInfo ticker,
      IndicatorResult indicators,
      TradingSignal signal,
      double buyScore,
      double sellScore
  ) {}

  // ─── Scan principal ───────────────────────────────────────────────────────

  @Cacheable(value = "scanResults", key = "#exchange + '-' + #interval")
  public MarketScanResult scan(TradingEnums.Exchange exchange,
      String interval,
      double minVolumeUSDT,
      int topSymbols) {

    long startTime = System.currentTimeMillis();
    double effectiveMinVolume = minVolumeUSDT > 0 ? minVolumeUSDT : DEFAULT_MIN_VOLUME_USDT;
    int effectiveTopSymbols   = topSymbols > 0 ? topSymbols : DEFAULT_TOP_SYMBOLS;

    log.info("Iniciando scan | Exchange: {} | Interval: {} | Min Volume: ${}M | Top: {}",
        exchange, interval,
        String.format("%.0f", effectiveMinVolume / 1_000_000),
        effectiveTopSymbols);

    // ─── 1. Buscar lista de ativos ─────────────────────────────────────────
    List<MarketDataService.TickerInfo> tickers = marketDataService.getTickers(
        exchange, effectiveMinVolume, effectiveTopSymbols);

    log.info("{} ativos encontrados para análise", tickers.size());

    // ─── 2. Analisar em paralelo ───────────────────────────────────────────
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    List<Future<ScanItem>> futures = new ArrayList<>();
    AtomicInteger processed = new AtomicInteger(0);
    AtomicInteger errors    = new AtomicInteger(0);

    for (MarketDataService.TickerInfo ticker : tickers) {
      Future<ScanItem> future = executor.submit(() -> {
        try {
          ScanItem item = analyzeAsset(ticker, exchange, interval);
          int count = processed.incrementAndGet();
          if (count % 10 == 0) {
            log.info("Analisados {}/{}", count, tickers.size());
          }
          return item;
        } catch (Exception e) {
          errors.incrementAndGet();
          log.debug("Erro ao analisar {}: {}", ticker.symbol(), e.getMessage());
          return null;
        }
      });
      futures.add(future);
    }

    executor.shutdown();
    try {
      executor.awaitTermination(120, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Scanner interrompido: {}", e.getMessage());
    }

    // ─── 3. Coletar resultados ─────────────────────────────────────────────
    List<ScanItem> scanItems = futures.stream()
        .map(f -> {
          try { return f.get(); } catch (Exception e) { return null; }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    log.info("Análise completa: {}/{} ativos processados ({} erros)",
        scanItems.size(), tickers.size(), errors.get());

    // ─── 4. Separar por tipo de sinal ─────────────────────────────────────
    // IMPORTANTE: ScanItem é um record — campos acessados via métodos com ()
    List<ScanItem> buyItems = scanItems.stream()
        .filter(i -> i.signal().getSignalType() == TradingEnums.SignalType.BUY
            || i.signal().getSignalType() == TradingEnums.SignalType.STRONG_BUY)
        .sorted(Comparator.comparingDouble(i -> -i.buyScore()))
        .collect(Collectors.toList());

    List<ScanItem> sellItems = scanItems.stream()
        .filter(i -> i.signal().getSignalType() == TradingEnums.SignalType.SELL
            || i.signal().getSignalType() == TradingEnums.SignalType.STRONG_SELL)
        .sorted(Comparator.comparingDouble(i -> -i.sellScore()))
        .collect(Collectors.toList());

    // ─── 5. Construir top 5 ───────────────────────────────────────────────
    List<RankedAsset> top5Buy  = buildRankedList(buyItems,  true,  TOP_N_RESULTS);
    List<RankedAsset> top5Sell = buildRankedList(sellItems, false, TOP_N_RESULTS);

    // ─── 6. Sentimento de mercado ─────────────────────────────────────────
    long bullCount = scanItems.stream()
        .filter(i -> i.signal().getSignalType() == TradingEnums.SignalType.BUY
            || i.signal().getSignalType() == TradingEnums.SignalType.STRONG_BUY)
        .count();
    long bearCount = scanItems.stream()
        .filter(i -> i.signal().getSignalType() == TradingEnums.SignalType.SELL
            || i.signal().getSignalType() == TradingEnums.SignalType.STRONG_SELL)
        .count();

    double total = scanItems.size();
    double bullPercent = total > 0 ? (bullCount / total) * 100 : 0;
    double bearPercent = total > 0 ? (bearCount / total) * 100 : 0;
    String sentiment = determineSentiment(bullPercent, bearPercent);

    long duration = System.currentTimeMillis() - startTime;
    log.info("Scan finalizado em {}ms | Touro: {}/{} | Urso: {}/{} | Top5 Buy: {} | Top5 Sell: {}",
        duration, bullCount, (int) total, bearCount, (int) total,
        top5Buy.size(), top5Sell.size());

    return MarketScanResult.builder()
        .scannedAt(Instant.now())
        .exchange(exchange.name())
        .interval(interval)
        .totalSymbolsScanned(tickers.size())
        .totalAnalyzed(scanItems.size())
        .totalBuySignals((int) bullCount)
        .totalSellSignals((int) bearCount)
        .totalNeutral((int) (total - bullCount - bearCount))
        .scanDurationMs(duration)
        .top5Buy(top5Buy)
        .top5Sell(top5Sell)
        .marketSentiment(sentiment)
        .marketSentimentLabel(getSentimentLabel(sentiment))
        .dominanceBullPercent(Math.round(bullPercent * 10.0) / 10.0)
        .dominanceBearPercent(Math.round(bearPercent * 10.0) / 10.0)
        .minVolume24hUSDT(effectiveMinVolume)
        .topNSymbols(effectiveTopSymbols)
        .build();
  }

  // ─── Análise individual de ativo ──────────────────────────────────────────

  private ScanItem analyzeAsset(MarketDataService.TickerInfo ticker,
      TradingEnums.Exchange exchange,
      String interval) {

    List<Candlestick> candles = switch (exchange) {
      case BINANCE -> binanceApiService.getKlines(ticker.symbol(), interval, CANDLES_FOR_SCAN);
      case BYBIT   -> bybitApiService.getKlines(ticker.symbol(), interval, CANDLES_FOR_SCAN);
    };

    IndicatorResult ind = indicatorService.calculate(candles);
    double buyScore  = scoringService.calculateBuyScore(ind);
    double sellScore = scoringService.calculateSellScore(ind);
    TradingSignal signal = signalGeneratorService.generate(candles, exchange, 0, 1.0);

    return new ScanItem(ticker, ind, signal, buyScore, sellScore);
  }

  // ─── Construção do ranking ────────────────────────────────────────────────

  private List<RankedAsset> buildRankedList(List<ScanItem> items, boolean isBuy, int limit) {
    List<RankedAsset> ranked = new ArrayList<>();
    List<ScanItem> top = items.stream().limit(limit).collect(Collectors.toList());

    for (int i = 0; i < top.size(); i++) {
      ScanItem item = top.get(i);
      int rank = i + 1;

      // ScanItem é record: usar métodos item.buyScore(), item.indicators(), etc.
      double score         = isBuy ? item.buyScore() : item.sellScore();
      IndicatorResult ind  = item.indicators();
      TradingSignal signal = item.signal();

      double[] individualScores = scoringService.getIndividualScores(ind, isBuy);
      double currentPrice = ind.getCurrentPrice();
      double recentLow    = currentPrice * 0.97;
      double recentHigh   = currentPrice * 1.03;

      double sl       = riskManagementService.calculateStopLoss(currentPrice, isBuy, ind, recentLow, recentHigh);
      double[] tps    = riskManagementService.calculateTakeProfits(currentPrice, sl, isBuy);
      double slPct    = Math.abs(currentPrice - sl) / currentPrice * 100;
      double leverage = riskManagementService.suggestLeverage(ind, slPct);

      TradingEnums.RiskLevel riskLevel = riskManagementService.assessRiskLevel(
          ind, signal.getSignalStrength());

      List<String> topAlerts = signal.getAlerts() != null
          ? signal.getAlerts().stream().limit(3).collect(Collectors.toList())
          : List.of();

      String reason = scoringService.buildRankingReason(ind, isBuy, rank);

      ranked.add(RankedAsset.builder()
          .rank(rank)
          .symbol(item.ticker().symbol())
          .exchange(signal.getExchange())
          .interval(signal.getInterval())
          .score(Math.round(score * 10.0) / 10.0)
          .scoreGrade(scoringService.getScoreGrade(score))
          .signalType(signal.getSignalType())
          .signalStrength(signal.getSignalStrength())
          .signalLabel(signal.getSignalLabel())
          .currentPrice(currentPrice)
          .priceChangePercent24h(Math.round(item.ticker().priceChangePercent() * 100.0) / 100.0)
          .volume24hUSDT(item.ticker().volume24hUSDT())
          .volumeRatio(Math.round(ind.getVolumeRatio() * 100.0) / 100.0)
          .rsi(Math.round(ind.getRsi() * 100.0) / 100.0)
          .rsiSignal(ind.getRsiSignal())
          .macdHistogram(Math.round(ind.getMacdHistogram() * 10000.0) / 10000.0)
          .macdCrossover(ind.getMacdCrossover())
          .trend(ind.getTrend())
          .entryPrice(currentPrice)
          .stopLoss(sl)
          .stopLossPercent(Math.round(slPct * 100.0) / 100.0)
          .takeProfit1(tps[0])
          .takeProfit2(tps[1])
          .takeProfit3(tps[2])
          .riskRewardRatio(Math.round(
              Math.abs(tps[1] - currentPrice) / Math.abs(currentPrice - sl) * 100.0) / 100.0)
          .suggestedLeverage(leverage)
          .riskLevel(riskLevel)
          .topAlerts(topAlerts)
          .estimatedDuration(signal.getEstimatedDuration())
          .rankingReason(reason)
          .rsiScore(Math.round(individualScores[0] * 10.0) / 10.0)
          .macdScore(Math.round(individualScores[1] * 10.0) / 10.0)
          .trendScore(Math.round(individualScores[2] * 10.0) / 10.0)
          .volumeScore(Math.round(individualScores[3] * 10.0) / 10.0)
          .momentumScore(Math.round(individualScores[4] * 10.0) / 10.0)
          .build());
    }

    return ranked;
  }

  // ─── Sentimento de Mercado ────────────────────────────────────────────────

  private String determineSentiment(double bullPercent, double bearPercent) {
    if (bullPercent >= 60) return "BULLISH";
    if (bearPercent >= 60) return "BEARISH";
    if (bullPercent >= 45 && bullPercent > bearPercent) return "SLIGHTLY_BULLISH";
    if (bearPercent >= 45 && bearPercent > bullPercent) return "SLIGHTLY_BEARISH";
    return "NEUTRAL";
  }

  private String getSentimentLabel(String sentiment) {
    return switch (sentiment) {
      case "BULLISH"          -> "Mercado em Alta 🐂";
      case "SLIGHTLY_BULLISH" -> "Levemente Otimista 📈";
      case "BEARISH"          -> "Mercado em Baixa 🐻";
      case "SLIGHTLY_BEARISH" -> "Levemente Pessimista 📉";
      default                 -> "Mercado Neutro ↔️";
    };
  }
}