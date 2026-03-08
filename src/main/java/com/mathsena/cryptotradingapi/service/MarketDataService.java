package com.mathsena.cryptotradingapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mathsena.cryptotradingapi.model.TradingEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serviço responsável por buscar o universo de ativos disponíveis
 * na exchange e filtrar por liquidez (volume 24h).
 *
 * Fonte primária: Binance Futures /fapi/v1/ticker/24hr (dados públicos, sem API key)
 * Fonte alternativa: Bybit /v5/market/tickers
 */
@Service
public class MarketDataService {

  private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

  @Value("${binance.api.base-url:https://fapi.binance.com}")
  private String binanceBaseUrl;

  @Value("${bybit.api.base-url:https://api.bybit.com}")
  private String bybitBaseUrl;

  // Símbolos excluídos por serem derivativos complexos ou tokens de baixa liquidez
  private static final Set<String> EXCLUDED_SYMBOLS = Set.of(
      "BTCDOMUSDT", "DEFIUSDT", "NFTUSDT", "COCOSUSDT"
  );

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public MarketDataService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
  }

  // ─── Ticker Record ────────────────────────────────────────────────────────

  public record TickerInfo(
      String symbol,
      double lastPrice,
      double priceChangePercent,
      double volume24hUSDT,
      double highPrice,
      double lowPrice,
      double volume
  ) {}

  // ─── Binance ──────────────────────────────────────────────────────────────

  /**
   * Busca todos os tickers de futuros USDT da Binance,
   * filtra por volume mínimo e retorna os N mais líquidos.
   *
   * @param minVolume24hUSDT Volume mínimo em USDT nas 24h (ex: 50_000_000 = 50M USDT)
   * @param topN             Quantos símbolos retornar (os mais líquidos)
   */
  public List<TickerInfo> getBinanceUSDTFuturesTickers(double minVolume24hUSDT, int topN) {
    log.info("Buscando tickers Binance Futures (volume mín: ${} USDT, top {})",
        String.format("%.0f", minVolume24hUSDT), topN);

    String endpoint = binanceBaseUrl + "/fapi/v1/ticker/24hr";

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(20))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException("Binance ticker API retornou HTTP " + response.statusCode());
      }

      JsonNode root = objectMapper.readTree(response.body());
      List<TickerInfo> tickers = new ArrayList<>();

      for (JsonNode node : root) {
        String symbol = node.get("symbol").asText();

        // Filtrar apenas pares USDT perpétuos
        if (!symbol.endsWith("USDT")) continue;
        if (EXCLUDED_SYMBOLS.contains(symbol)) continue;

        double lastPrice = node.get("lastPrice").asDouble();
        double priceChangePercent = node.get("priceChangePercent").asDouble();
        double volume = node.get("volume").asDouble();
        double quoteVolume = node.get("quoteVolume").asDouble(); // Volume em USDT
        double highPrice = node.get("highPrice").asDouble();
        double lowPrice = node.get("lowPrice").asDouble();

        // Filtro de volume mínimo
        if (quoteVolume < minVolume24hUSDT) continue;
        // Filtro de preço mínimo (evita tokens de 0.000001)
        if (lastPrice < 0.0001) continue;

        tickers.add(new TickerInfo(symbol, lastPrice, priceChangePercent,
            quoteVolume, highPrice, lowPrice, volume));
      }

      // Ordenar por volume (mais líquidos primeiro) e pegar top N
      List<TickerInfo> result = tickers.stream()
          .sorted(Comparator.comparingDouble(TickerInfo::volume24hUSDT).reversed())
          .limit(topN)
          .collect(Collectors.toList());

      log.info("Encontrados {} pares USDT com volume > ${} | Usando top {}",
          tickers.size(), String.format("%.0f", minVolume24hUSDT), result.size());

      return result;

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha ao buscar tickers da Binance: " + e.getMessage(), e);
    }
  }

  // ─── Bybit ────────────────────────────────────────────────────────────────

  /**
   * Busca tickers de futuros lineares (USDT) da Bybit V5.
   */
  public List<TickerInfo> getBybitUSDTFuturesTickers(double minVolume24hUSDT, int topN) {
    log.info("Buscando tickers Bybit Linear Futures (volume mín: ${} USDT, top {})",
        String.format("%.0f", minVolume24hUSDT), topN);

    String endpoint = bybitBaseUrl + "/v5/market/tickers?category=linear";

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(20))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode list = root.path("result").path("list");

      List<TickerInfo> tickers = new ArrayList<>();

      for (JsonNode node : list) {
        String symbol = node.get("symbol").asText();
        if (!symbol.endsWith("USDT")) continue;
        if (EXCLUDED_SYMBOLS.contains(symbol)) continue;

        double lastPrice = node.get("lastPrice").asDouble();
        double priceChangePercent = node.get("price24hPcnt").asDouble() * 100;
        double volume24h = node.get("volume24h").asDouble();
        double turnover24h = node.get("turnover24h").asDouble(); // Volume em USDT
        double highPrice = node.get("highPrice24h").asDouble();
        double lowPrice = node.get("lowPrice24h").asDouble();

        if (turnover24h < minVolume24hUSDT) continue;
        if (lastPrice < 0.0001) continue;

        tickers.add(new TickerInfo(symbol, lastPrice, priceChangePercent,
            turnover24h, highPrice, lowPrice, volume24h));
      }

      return tickers.stream()
          .sorted(Comparator.comparingDouble(TickerInfo::volume24hUSDT).reversed())
          .limit(topN)
          .collect(Collectors.toList());

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha ao buscar tickers da Bybit: " + e.getMessage(), e);
    }
  }

  /**
   * Busca tickers da exchange configurada.
   */
  public List<TickerInfo> getTickers(TradingEnums.Exchange exchange,
      double minVolume24hUSDT, int topN) {
    return switch (exchange) {
      case BINANCE -> getBinanceUSDTFuturesTickers(minVolume24hUSDT, topN);
      case BYBIT   -> getBybitUSDTFuturesTickers(minVolume24hUSDT, topN);
    };
  }
}