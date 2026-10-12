package com.gov.docregistry.web;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Describes granted capabilities without exposing credentials or identity claims. */
@RestController
public class SessionController {
  @GetMapping("/api/session")
  public Map<String, Object> session(Authentication authentication) {
    return Map.of(
        "roles",
        authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList());
  }
}
