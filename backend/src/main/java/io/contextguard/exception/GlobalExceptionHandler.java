package io.contextguard.exception;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(
            MethodArgumentNotValidException ex
    ) {
        Map<String, String> fieldErrors = new HashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                                 fieldErrors.put(
                                         error.getField(),
                                         error.getDefaultMessage()
                                 )
                );

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", Instant.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation Failed");
        response.put("fields", fieldErrors);

        return ResponseEntity.badRequest().body(response);
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleInvalidEnum(
            HttpMessageNotReadableException ex
    ) {
        Throwable rootCause = ex.getMostSpecificCause();

        if (rootCause instanceof IllegalArgumentException) {
            return ResponseEntity
                           .badRequest()
                           .body(Map.of(
                                   "error", rootCause.getMessage()
                           ));
        }

        return ResponseEntity
                       .badRequest()
                       .body(Map.of(
                               "error", "Invalid request payload"
                       ));
    }
}

