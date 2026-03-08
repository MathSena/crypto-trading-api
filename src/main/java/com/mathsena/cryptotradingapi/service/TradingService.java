package com.mathsena.cryptotradingapi.service;

import com.mathsena.cryptotradingapi.model.Candlestick;
import com.mathsena.cryptotradingapi.model.IndicatorResult;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import com.mathsena.cryptotradingapi.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serviço principal que orquestra:
 * 1. Busca de dados da Exchange (Binance/Bybit)
 * 2. Cálculo de indicadores
 * 3. Geração do sinal completo com SL/TP/alertas
 */
@Service
public class TradingService {

  private static final Logger log = LoggerFactory.getLogger(TradingService.class);
  private static final int CANDLES_LIMIT = 200;

  private final BinanceApiService binanceApiService;
  private final BybitApiService bybitApiService;
  private final TechnicalIndicatorService indicatorService;
  private final SignalGeneratorService signalGeneratorService;

  public TradingService(BinanceApiService binanceApiService,
      BybitApiService bybitApiService,
      TechnicalIndicatorService indicatorService,
      SignalGeneratorService signalGeneratorService) {
    this.binanceApiService = binanceApiService;
    this.bybitApiService = bybitApiService;
    this.indicatorService = indicatorService;
    this.signalGeneratorService = signalGeneratorService;
  }

  /**
   * Gera sinal completo de trading para um ativo.
   *
   * @param symbol         Ex: "BTCUSDT"
   * @param interval       Ex: "1h", "4h", "15m"
   * @param exchange       BINANCE ou BYBIT
   * @param accountBalance Saldo opcional para cálculo de tamanho de posição
   * @param riskPercent    % de risco por trade (padrão: 1%)
   */
  @Cacheable(value = "signals", key = "#symbol + '-' + #interval + '-' + #exchange")
  public TradingSignal getSignal(String symbol, String interval,
      TradingEnums.Exchange exchange,
      double accountBalance, double riskPercent) {

    log.info("Processando sinal: {} | {} | {}", symbol, interval, exchange);

    // 1. Buscar candles da exchange
    List<Candlestick> candles = fetchCandles(symbol, interval, exchange);

    // 2. Gerar sinal completo
    return signalGeneratorService.generate(candles, exchange, accountBalance, riskPercent);
  }

  /**
   * Retorna apenas os indicadores calculados, sem gerar sinal.
   */
  @Cacheable(value = "indicators", key = "#symbol + '-' + #interval + '-' + #exchange")
  public IndicatorResult getIndicators(String symbol, String interval,
      TradingEnums.Exchange exchange) {

    List<Candlestick> candles = fetchCandles(symbol, interval, exchange);
    return indicatorService.calculate(candles);
  }

  /**
   * Retorna os candles brutos da exchange.
   */
  public List<Candlestick> getCandles(String symbol, String interval,
      TradingEnums.Exchange exchange, int limit) {
    return fetchCandles(symbol, interval, exchange, Math.min(limit, CANDLES_LIMIT));
  }

  /**
   * Retorna o preço atual do ativo.
   */
  public double getCurrentPrice(String symbol, TradingEnums.Exchange exchange) {
    return switch (exchange) {
      case BINANCE -> binanceApiService.getCurrentPrice(symbol);
      case BYBIT -> bybitApiService.getCurrentPrice(symbol);
    };
  }

  // ─── Privados ─────────────────────────────────────────────────────────────

  private List<Candlestick> fetchCandles(String symbol, String interval,
      TradingEnums.Exchange exchange) {
    return fetchCandles(symbol, interval, exchange, CANDLES_LIMIT);
  }

  private List<Candlestick> fetchCandles(String symbol, String interval,
      TradingEnums.Exchange exchange, int limit) {
    return switch (exchange) {
      case BINANCE -> binanceApiService.getKlines(symbol, interval, limit);
      case BYBIT -> bybitApiService.getKlines(symbol, interval, limit);
    };
  }
}