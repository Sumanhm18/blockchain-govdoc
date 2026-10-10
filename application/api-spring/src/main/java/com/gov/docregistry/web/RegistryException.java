package com.gov.docregistry.web;

import org.springframework.http.HttpStatus;

public class RegistryException extends RuntimeException {
  private final String code;
  private final HttpStatus status;

  public RegistryException(String code, HttpStatus status, String message) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public static RegistryException validation(String message) {
    return new RegistryException("VALIDATION", HttpStatus.BAD_REQUEST, message);
  }

  public static RegistryException unavailable() {
    return new RegistryException(
        "DEPENDENCY_UNAVAILABLE",
        HttpStatus.SERVICE_UNAVAILABLE,
        "A required service is unavailable; retry later");
  }

  public static RegistryException forbidden() {
    return new RegistryException(
        "FORBIDDEN", HttpStatus.FORBIDDEN, "Operation is not permitted for this identity");
  }
}
