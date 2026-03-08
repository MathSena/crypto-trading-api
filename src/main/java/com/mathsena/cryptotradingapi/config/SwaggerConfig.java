package com.mathsena.cryptotradingapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(new Info()
            .title("🚀 Crypto Trading Signal API")
            .version("1.0.0")
            .description("""
                                API de análise técnica para futuros de cripto.
                                
                                **Funcionalidades:**
                                - Sinais de compra/venda baseados em RSI, MACD e EMAs
                                - Stop Loss e Take Profit calculados tecnicamente
                                - Gestão de risco com alavancagem e tamanho de posição
                                - Alertas de entrada, saída e ajuste de alvos
                                - Previsão de duração da trade
                                - Integração com Binance Futures e Bybit
                                
                                **Como usar:**
                                1. Acesse `GET /api/v1/signal?symbol=BTCUSDT&interval=1h`
                                2. Leia o sinal, stop loss e take profits
                                3. Monitore os alertas de saída
                                """)
            .contact(new Contact().name("Crypto Trading Bot")))
        .servers(List.of(
            new Server().url("http://localhost:8080").description("Local")
        ));
  }
}