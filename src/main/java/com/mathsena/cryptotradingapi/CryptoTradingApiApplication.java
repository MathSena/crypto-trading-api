package com.mathsena.cryptotradingapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class CryptoTradingApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(CryptoTradingApiApplication.class, args);
    System.out.println("""
                ╔══════════════════════════════════════════╗
                ║   🚀 Crypto Trading Signal API Online!   ║
                ║   Swagger: http://localhost:8080/swagger-ui.html ║
                ╚══════════════════════════════════════════╝
                """);
  }

}
