package com.gov.docregistry.web;

import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.CommitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice
public class GlobalExceptionHandler {
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }

    @ExceptionHandler(EndorseException.class)
    public ResponseEntity<Map<String, Object>> handleEndorse(EndorseException ex, HttpServletRequest request) {
        String full = extractDeepMessage(ex);
        String lower = full.toLowerCase();
        boolean isRegisterEndpoint = request != null && "POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/documents");

        // Duplicate / already exists -> 409
        if (isRegisterEndpoint || lower.contains("exists") || lower.contains("already") || lower.contains("duplicate") || lower.contains("key already") || lower.contains("mvcc")) {
            return build(HttpStatus.CONFLICT, full);
        }
        // Not found patterns -> 404
        if (lower.contains("does not exist") || lower.contains("not found") || lower.contains("no such key") || lower.contains("missing key")) {
            return build(HttpStatus.NOT_FOUND, full);
        }
        // Validation / bad input patterns -> 400
        if (lower.contains("invalid") || lower.contains("missing") || lower.contains("empty") || lower.contains("bad request") || lower.contains("illegal argument")) {
            return build(HttpStatus.BAD_REQUEST, full);
        }
        // Other endorsement failures -> 502
        return build(HttpStatus.BAD_GATEWAY, full);
    }

    @ExceptionHandler({SubmitException.class, CommitException.class})
    public ResponseEntity<Map<String, Object>> handleSubmitCommit(Exception ex) {
        String msg = extractDeepMessage(ex);
        return build(HttpStatus.BAD_GATEWAY, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        String msg = extractDeepMessage(ex);
        // Try to map generic not-found text to 404
        String l = msg.toLowerCase();
        if (l.contains("does not exist") || l.contains("not found") || l.contains("no such key")) {
            return build(HttpStatus.NOT_FOUND, msg);
        }
        // Map obvious bad input signals to 400
        if (l.contains("invalid") || l.contains("missing") || l.contains("empty") || l.contains("illegal argument")) {
            return build(HttpStatus.BAD_REQUEST, msg);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, msg);
    }

    private String extractDeepMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        boolean first = true;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) {
                if (!first) sb.append(" | cause: ");
                sb.append(m);
                first = false;
            }
            cur = cur.getCause();
        }
        return sb.length() > 0 ? sb.toString() : ex.toString();
    }
}


