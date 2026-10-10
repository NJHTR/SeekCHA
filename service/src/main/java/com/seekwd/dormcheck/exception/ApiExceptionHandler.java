package com.seekwd.dormcheck.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(ApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<Map<String, String>> handleValidation(Exception ignored) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", "request validation failed"));
    }
}
