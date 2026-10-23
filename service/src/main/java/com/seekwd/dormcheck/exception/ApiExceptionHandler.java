package com.seekwd.dormcheck.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(ApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<Map<String, String>> handleValidation(Exception ignored) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", "request validation failed"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception error) {
        // Never reflect JDBC/HTTP/parser exception text to an untrusted client.
        log.error("unhandled API exception type={}", error.getClass().getName());
        return ResponseEntity.internalServerError().body(Map.of("detail", "服务暂时不可用，请稍后重试"));
    }
}
