package com.gov.docregistry.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class ClientConfigController {
  private final String mode, issuer, client;
  public ClientConfigController(@Value("${registry.auth.mode:basic}") String mode,
      @Value("${registry.auth.issuer-uri:}") String issuer,
      @Value("${registry.auth.browser-client:}") String client) {
    this.mode = mode; this.issuer = issuer; this.client = client;
  }
  @GetMapping("/api/client-config")
  public Map<String, String> config() {
    return Map.of("mode", mode, "issuer", mode.equals("jwt") ? issuer : "", "clientId", mode.equals("jwt") ? client : "");
  }
}
