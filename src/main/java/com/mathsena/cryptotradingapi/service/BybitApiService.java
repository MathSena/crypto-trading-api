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
 * Client para a API REST da Bybit (api.bybit.com).
 * Compatível com a V5 API da Bybit para futuros perpétuos.
 */
@Service
public class BybitApiService {

  private static final Logger log = LoggerFactory.getLogger(BybitApiService.class);

  @Value("${bybit.api.base-url:https://api.bybit.com}")
  private String baseUrl;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public BybitApiService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /**
   * Busca candles (klines) da Bybit V5 API para futuros lineares (USDT).
   */
  public List<Candlestick> getKlines(String symbol, String interval, int limit) {
    String bybitInterval = convertInterval(interval);
    String endpoint = String.format(
        "%s/v5/market/kline?category=linear&symbol=%s&interval=%s&limit=%d",
        baseUrl, symbol.toUpperCase(), bybitInterval, limit);

    log.info("Buscando klines Bybit: {} | {} | {} candles", symbol, interval, limit);

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException("Bybit API error: HTTP " + response.statusCode());
      }

      return parseKlines(symbol, interval, response.body());

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha na comunicação com Bybit: " + e.getMessage(), e);
    }
  }

  /**
   * Retorna o preço atual (mark price) via Bybit V5.
   */
  public double getCurrentPrice(String symbol) {
    String endpoint = String.format(
        "%s/v5/market/tickers?category=linear&symbol=%s",
        baseUrl, symbol.toUpperCase());

    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(endpoint))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode list = root.path("result").path("list");

      if (list.isArray() && list.size() > 0) {
        return list.get(0).get("markPrice").asDouble();
      }

      throw new RuntimeException("Preço não encontrado para: " + symbol);

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Falha ao buscar preço na Bybit: " + e.getMessage(), e);
    }
  }

  // ─── Parsing ──────────────────────────────────────────────────────────────

  private List<Candlestick> parseKlines(String symbol, String interval, String json) {
    List<Candlestick> candles = new ArrayList<>();

    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode list = root.path("result").path("list");

      // Bybit retorna do mais recente ao mais antigo — reverter
      List<JsonNode> rows = new ArrayList<>();
      list.forEach(rows::add);
      java.util.Collections.reverse(rows);

      for (JsonNode row : rows) {
        // Formato Bybit V5: [startTime, open, high, low, close, volume, turnover]
        Candlestick candle = Candlestick.builder()
            .symbol(symbol)
            .interval(interval)
            .openTime(Instant.ofEpochMilli(row.get(0).asLong()))
            .open(new BigDecimal(row.get(1).asText()))
            .high(new BigDecimal(row.get(2).asText()))
            .low(new BigDecimal(row.get(3).asText()))
            .close(new BigDecimal(row.get(4).asText()))
            .volume(new BigDecimal(row.get(5).asText()))
            .isClosed(true)
            .build();

        candles.add(candle);
      }

      return candles;

    } catch (Exception e) {
      throw new RuntimeException("Erro ao parsear klines da Bybit: " + e.getMessage(), e);
    }
  }

  /**
   * Converte intervalos padrão (1h, 4h) para o formato da Bybit (60, 240).
   */
  private String convertInterval(String interval) {
    return switch (interval) {
      case "1m"  -> "1";
      case "3m"  -> "3";
      case "5m"  -> "5";
      case "15m" -> "15";
      case "30m" -> "30";
      case "1h"  -> "60";
      case "2h"  -> "120";
      case "4h"  -> "240";
      case "6h"  -> "360";
      case "12h" -> "720";
      case "1d"  -> "D";
      case "1w"  -> "W";
      case "1M"  -> "M";
      default -> interval;
    };
  }
}