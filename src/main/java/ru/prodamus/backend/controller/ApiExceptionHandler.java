package ru.prodamus.backend.controller;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> validation(Exception ex) {
        String message = "Проверьте переданные данные.";
        if (ex instanceof MethodArgumentNotValidException valid && valid.getBindingResult().getFieldError() != null) {
            message = valid.getBindingResult().getFieldError().getDefaultMessage();
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException ex) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "Такая запись уже существует или используется другими объектами.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("Unexpected API error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера.");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}
