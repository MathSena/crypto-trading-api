package com.mathsena.cryptotradingapi.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler global de exceções para a API REST.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
    log.warn("Argumento inválido: {}", e.getMessage());
    return ResponseEntity.badRequest().body(error(e.getMessage(), "INVALID_ARGUMENT"));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
    log.error("Erro de runtime: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(error(e.getMessage(), "SERVICE_ERROR"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
    log.error("Erro inesperado: {}", e.getMessage(), e);
    return ResponseEntity.internalServerError()
        .body(error("Erro interno do servidor: " + e.getMessage(), "INTERNAL_ERROR"));
  }

  private Map<String, Object> error(String message, String code) {
    Map<String, Object> body = new HashMap<>();
    body.put("success", false);
    body.put("error", message);
    body.put("errorCode", code);
    body.put("timestamp", Instant.now().toString());
    return body;
  }
}