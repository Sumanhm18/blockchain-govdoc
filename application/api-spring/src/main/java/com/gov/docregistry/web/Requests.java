package com.gov.docregistry.web;

import jakarta.validation.constraints.*;

public final class Requests {
  private Requests() {}

  public static final String ID = "^[A-Z0-9-]{3,50}$";
  public static final String HASH = "^sha256:[0-9a-f]{64}$";

  public record Register(
      @NotBlank @Pattern(regexp = ID) String docId,
      @NotBlank @Pattern(regexp = HASH) String hash,
      @Size(max = 128) String issuer,
      @Size(max = 128) String subject,
      @Size(max = 16384) String metadataJson) {}

  public record Verify(@NotBlank @Pattern(regexp = HASH) String hash) {}

  public record Revoke(@NotBlank @Size(max = 500) String reason) {}
}
