package com.mathsena.cryptotradingapi.service;

import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.MtfSignal;
import com.mathsena.cryptotradingapi.model.TradingEnums;
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
 * Serviço de varredura do mercado com as estratégias MTF.
 *
 * Analisa em paralelo os principais ativos e retorna:
 *  - Ativos com Trend Rider ativo
 *  - Ativos com Sniper Scalp ativo
 *  - Ativos com permissão concedida aguardando gatilho
 *  - Sentimento macro geral (baseado em 1h)
 */
@Service
public class MtfScannerService {

  private static final Logger log = LoggerFactory.getLogger(MtfScannerService.class);
  private static final int THREAD_POOL_SIZE = 10;
  private static final int CANDLES_LIMIT    = 100;
  private static final int MAX_RESULTS      = 10;

  private final MarketDataService marketDataService;
  private final BinanceApiService binanceApiService;
  private final BybitApiService bybitApiService;
  private final TechnicalIndicatorService indicatorService;
  private final MtfStrategyService mtfStrategyService;

  public MtfScannerService(MarketDataService marketDataService,
      BinanceApiService binanceApiService,
      BybitApiService bybitApiService,
      TechnicalIndicatorService indicatorService,
      MtfStrategyService mtfStrategyService) {
    this.marketDataService = marketDataService;
    this.binanceApiService = binanceApiService;
    this.bybitApiService = bybitApiService;
    this.indicatorService = indicatorService;
    this.mtfStrategyService = mtfStrategyService;
  }

  // ─── Resultado do scan ────────────────────────────────────────────────────

  public record MtfScanResult(
      Instant scannedAt,
      String exchange,
      int totalScanned,
      int totalAnalyzed,
      long scanDurationMs,
      String macroSentiment,
      String macroAdvice,
      double bullishPercent,
      double bearishPercent,
      List<MtfScanItem> trendRiderActive,
      List<MtfScanItem> sniperScalpActive,
      List<MtfScanItem> waitingWithPermission
  ) {}

  public record MtfScanItem(
      String symbol,
      TradingEnums.StrategyType strategyType,
      TradingEnums.StrategyStatus status,
      double entryPrice,
      double stopLoss,
      double stopLossPercent,
      double tp1,
      double tp2,
      double leverage,
      double rsi3m,
      double rsi1h,
      String macd3m,
      String trend1h,
      String statusReason
  ) {}

  // ─── Scan principal ───────────────────────────────────────────────────────

  @Cacheable(value = "mtfScanResults", key = "#exchange")
  public MtfScanResult scan(TradingEnums.Exchange exchange,
      double minVolumeUSDT, int topSymbols) {

    long startTime = System.currentTimeMillis();
    log.info("Iniciando MTF scan | Exchange: {} | Min Volume: ${}M | Top: {}",
        exchange, String.format("%.0f", minVolumeUSDT / 1_000_000), topSymbols);

    // 1. Buscar lista de ativos
    List<MarketDataService.TickerInfo> tickers =
        marketDataService.getTickers(exchange, minVolumeUSDT, topSymbols);
    log.info("{} ativos para análise MTF", tickers.size());

    // 2. Analisar em paralelo
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    List<Future<MtfScanItem>> futures = new ArrayList<>();
    AtomicInteger processed = new AtomicInteger(0);
    AtomicInteger errors    = new AtomicInteger(0);

    for (MarketDataService.TickerInfo ticker : tickers) {
      futures.add(executor.submit(() -> {
        try {
          MtfScanItem item = analyzeAsset(ticker.symbol(), exchange);
          int count = processed.incrementAndGet();
          if (count % 10 == 0) log.info("MTF analisados {}/{}", count, tickers.size());
          return item;
        } catch (Exception e) {
          errors.incrementAndGet();
          log.debug("Erro MTF em {}: {}", ticker.symbol(), e.getMessage());
          return null;
        }
      }));
    }

    executor.shutdown();
    try {
      executor.awaitTermination(120, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // 3. Coletar resultados
    List<MtfScanItem> allItems = futures.stream()
        .map(f -> { try { return f.get(); } catch (Exception e) { return null; } })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    // 4. Separar por categoria
    List<MtfScanItem> trendRiderActive = allItems.stream()
        .filter(i -> i.strategyType() == TradingEnums.StrategyType.TREND_RIDER
            && i.status() == TradingEnums.StrategyStatus.ACTIVE)
        .limit(MAX_RESULTS)
        .collect(Collectors.toList());

    List<MtfScanItem> sniperScalpActive = allItems.stream()
        .filter(i -> i.strategyType() == TradingEnums.StrategyType.SNIPER_SCALP
            && i.status() == TradingEnums.StrategyStatus.ACTIVE)
        .limit(MAX_RESULTS)
        .collect(Collectors.toList());

    List<MtfScanItem> waitingWithPermission = allItems.stream()
        .filter(i -> i.status() == TradingEnums.StrategyStatus.WAITING)
        .limit(MAX_RESULTS)
        .collect(Collectors.toList());

    // 5. Sentimento macro (baseado nos 1h dos ativos analisados)
    long bullish1h = allItems.stream()
        .filter(i -> "UPTREND".equals(i.trend1h()) || "STRONG_UPTREND".equals(i.trend1h()))
        .count();
    long bearish1h = allItems.stream()
        .filter(i -> "DOWNTREND".equals(i.trend1h()) || "STRONG_DOWNTREND".equals(i.trend1h()))
        .count();

    double total = allItems.size();
    double bullPct = total > 0 ? Math.round((bullish1h / total) * 1000.0) / 10.0 : 0;
    double bearPct = total > 0 ? Math.round((bearish1h / total) * 1000.0) / 10.0 : 0;
    String macroSentiment = determineMacroSentiment(bullPct, bearPct);
    String macroAdvice    = buildMacroAdvice(macroSentiment);

    long duration = System.currentTimeMillis() - startTime;
    log.info("MTF scan completo em {}ms | TrendRider: {} | Sniper: {} | Waiting: {}",
        duration, trendRiderActive.size(), sniperScalpActive.size(), waitingWithPermission.size());

    return new MtfScanResult(
        Instant.now(), exchange.name(), tickers.size(), allItems.size(), duration,
        macroSentiment, macroAdvice, bullPct, bearPct,
        trendRiderActive, sniperScalpActive, waitingWithPermission
    );
  }

  // ─── Análise de um ativo ──────────────────────────────────────────────────

  private MtfScanItem analyzeAsset(String symbol, TradingEnums.Exchange exchange) {
    // Buscar os dois timeframes
    List<Candlestick> candles1h = switch (exchange) {
      case BINANCE -> binanceApiService.getKlines(symbol, "1h", CANDLES_LIMIT);
      case BYBIT   -> bybitApiService.getKlines(symbol, "1h", CANDLES_LIMIT);
    };
    List<Candlestick> candles3m = switch (exchange) {
      case BINANCE -> binanceApiService.getKlines(symbol, "3m", CANDLES_LIMIT);
      case BYBIT   -> bybitApiService.getKlines(symbol, "3m", CANDLES_LIMIT);
    };

    IndicatorResult ind1h = indicatorService.calculate(candles1h);
    IndicatorResult ind3m = indicatorService.calculate(candles3m);

    // Avaliar estratégias (reutiliza a lógica do MtfStrategyService via cache)
    MtfSignal signal = mtfStrategyService.evaluate(symbol, exchange);

    return new MtfScanItem(
        symbol,
        signal.getStrategyType(),
        signal.getStatus(),
        signal.getEntryPrice(),
        signal.getStopLoss(),
        signal.getStopLossPercent(),
        signal.getTakeProfit1(),
        signal.getTakeProfit2(),
        signal.getSuggestedLeverage(),
        Math.round(ind3m.getRsi() * 100.0) / 100.0,
        Math.round(ind1h.getRsi() * 100.0) / 100.0,
        ind3m.getMacdCrossover(),
        ind1h.getTrend().name(),
        signal.getStatusReason()
    );
  }

  // ─── Sentimento Macro ─────────────────────────────────────────────────────

  private String determineMacroSentiment(double bullPct, double bearPct) {
    if (bullPct >= 55) return "BULLISH";
    if (bearPct >= 55) return "BEARISH";
    if (bullPct > bearPct) return "SLIGHTLY_BULLISH";
    if (bearPct > bullPct) return "SLIGHTLY_BEARISH";
    return "NEUTRAL";
  }

  private String buildMacroAdvice(String sentiment) {
    return switch (sentiment) {
      case "BULLISH"          -> "✅ Mercado em alta no 1h — Ambas as estratégias liberadas. Priorizar Trend Rider.";
      case "SLIGHTLY_BULLISH" -> "📈 Leve otimismo no 1h — Trend Rider com cautela, Sniper liberado.";
      case "BEARISH"          -> "⚠️ Mercado em baixa no 1h — Ignorar Trend Rider. Apenas Sniper Scalp rápido.";
      case "SLIGHTLY_BEARISH" -> "📉 Leve pessimismo — Priorizar Sniper Scalp com metas conservadoras.";
      default                 -> "↔️ Mercado neutro — Ambas as estratégias com critérios normais.";
    };
  }
}
