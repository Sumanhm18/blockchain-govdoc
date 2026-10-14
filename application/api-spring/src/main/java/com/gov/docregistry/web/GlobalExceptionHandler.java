package com.gov.docregistry.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private ResponseEntity<Map<String, Object>> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "status",
                status.value(),
                "code",
                code,
                "error",
                status.getReasonPhrase(),
                "message",
                message));
  }

  @ExceptionHandler(RegistryException.class)
  public ResponseEntity<Map<String, Object>> registry(RegistryException e) {
    return error(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class
  })
  public ResponseEntity<Map<String, Object>> validation(Exception e) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION", "Request fields are missing or invalid");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
    return error(
        HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Upload exceeds the configured limit");
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, Object>> forbidden(Exception e) {
    return registry(RegistryException.forbidden());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse response)
      return error(
          HttpStatus.valueOf(response.getStatusCode().value()),
          "REQUEST_ERROR",
          "Request cannot be processed");
    String incident = UUID.randomUUID().toString();
    Throwable root = e;
    for (int depth = 0; depth < 10 && root.getCause() != null && root.getCause() != root; depth++)
      root = root.getCause();
    // Class names and stack locations aid diagnosis without logging exception messages or data.
    log.error(
        "Unexpected registry error incident={} type={} root={} frames={}",
        incident,
        e.getClass().getSimpleName(),
        root.getClass().getName(),
        java.util.Arrays.toString(
            java.util.Arrays.stream(root.getStackTrace()).limit(12).toArray()));
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Request failed; reference " + incident);
  }
}
