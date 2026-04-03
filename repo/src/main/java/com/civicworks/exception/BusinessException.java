package com.civicworks.exception;

import org.springframework.http.HttpStatus;
import java.util.Map;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public BusinessException(String message, HttpStatus status, String code, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public BusinessException(String message, HttpStatus status, String code) {
        this(message, status, code, Map.of());
    }

    public BusinessException(String message, HttpStatus status) {
        this(message, status, "BUSINESS_RULE_VIOLATION", Map.of());
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
