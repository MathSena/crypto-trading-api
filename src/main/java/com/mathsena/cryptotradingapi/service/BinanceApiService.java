package com.mathsena.cryptotradingapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mathsena.cryptotradingapi.model.Candlestick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client para a API REST da Binance Futures (fapi.binance.com).
 * Busca candles OHLCV, preço atual e dados de mercado.
 */
@Service
public class BinanceApiService {

  private static final Logger log = LoggerFactory.getLogger(BinanceApiService.class);

  @Value("${binance.api.base-url:https://fapi.binance.com}")
  private String baseUrl;

  @Value("${binance.api.testnet:false}")
  private boolean testnet;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public BinanceApiService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /**
   * Busca candles históricos (klines) para o par e timeframe informados.
   *
   * @param symbol   Par de trading, ex: "BTCUSDT"
   * @param interval Timeframe, ex: "1h", "4h", "1d"
   * @param limit    Quantidade de candles (max 1500 na Binance Futures)
   */
  public List<Candlestick> getKlines(String symbol, String interval, int limit) {
    String endpoint = String.format("%s/fapi/v1/klines?symbol=%s&interval=%s&limit=%d",
        baseUrl, symbol.toUpperCase(), interval, limit);

    log.info("Buscando klines: {} | {} | {} candles", symbol, interval, limit);

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.error("Binance API error: HTTP {} - {}", response.statusCode(), response.body());
        throw new RuntimeException("Binance API retornou HTTP " + response.statusCode()
            + ". Verifique o símbolo e a conexão.");
      }

      return parseKlines(symbol, interval, response.body());

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      log.error("Erro ao buscar klines da Binance: {}", e.getMessage());
      throw new RuntimeException("Falha na comunicação com a Binance: " + e.getMessage(), e);
    }
  }

  /**
   * Retorna o preço atual (mark price) de um ativo na Binance Futures.
   */
  public double getCurrentPrice(String symbol) {
    String endpoint = String.format("%s/fapi/v1/premiumIndex?symbol=%s",
        baseUrl, symbol.toUpperCase());

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException("Erro ao buscar preço: HTTP " + response.statusCode());
      }

      JsonNode node = objectMapper.readTree(response.body());
      return node.get("markPrice").asDouble();

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha ao buscar preço atual: " + e.getMessage(), e);
    }
  }

  /**
   * Retorna ticker de 24h com variação de preço e volume.
   */
  public JsonNode get24hTicker(String symbol) {
    String endpoint = String.format("%s/fapi/v1/ticker/24hr?symbol=%s",
        baseUrl, symbol.toUpperCase());

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException("Erro ao buscar ticker 24h: HTTP " + response.statusCode());
      }

      return objectMapper.readTree(response.body());

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha ao buscar ticker 24h: " + e.getMessage(), e);
    }
  }

  /**
   * Valida se um símbolo existe na Binance Futures.
   */
  public boolean isValidSymbol(String symbol) {
    try {
      String endpoint = String.format("%s/fapi/v1/exchangeInfo", baseUrl);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode symbols = root.get("symbols");

      for (JsonNode s : symbols) {
        if (symbol.toUpperCase().equals(s.get("symbol").asText())) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      log.warn("Não foi possível validar símbolo: {}", e.getMessage());
      return true; // assume válido se não conseguir verificar
    }
  }

  // ─── Parsing ──────────────────────────────────────────────────────────────

  private List<Candlestick> parseKlines(String symbol, String interval, String json) {
    List<Candlestick> candles = new ArrayList<>();

    try {
      JsonNode root = objectMapper.readTree(json);

      for (JsonNode kline : root) {
        // Formato Binance: [openTime, open, high, low, close, volume,
        //                   closeTime, quoteVolume, trades, ...]
        Candlestick candle = Candlestick.builder()
            .symbol(symbol)
            .interval(interval)
            .openTime(Instant.ofEpochMilli(kline.get(0).asLong()))
            .open(new BigDecimal(kline.get(1).asText()))
            .high(new BigDecimal(kline.get(2).asText()))
            .low(new BigDecimal(kline.get(3).asText()))
            .close(new BigDecimal(kline.get(4).asText()))
            .volume(new BigDecimal(kline.get(5).asText()))
            .closeTime(Instant.ofEpochMilli(kline.get(6).asLong()))
            .quoteVolume(new BigDecimal(kline.get(7).asText()))
            .numberOfTrades(kline.get(8).asLong())
            .isClosed(true)
            .build();

        candles.add(candle);
      }

      log.debug("Parsed {} candles para {} {}", candles.size(), symbol, interval);
      return candles;

    } catch (Exception e) {
      throw new RuntimeException("Erro ao parsear klines da Binance: " + e.getMessage(), e);
    }
  }
}