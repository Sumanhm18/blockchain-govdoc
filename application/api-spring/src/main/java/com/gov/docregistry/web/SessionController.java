package com.gov.docregistry.web;

import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns capabilities and the authenticated citizen's own assignment reference; never credentials. */
@RestController
public class SessionController {
  @GetMapping("/api/session")
  public Map<String, Object> session(Authentication authentication) {
    Map<String, Object> session = new LinkedHashMap<>(Map.of(
        "roles",
        authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList()));
    if (authentication instanceof JwtAuthenticationToken jwt
        && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CITIZEN")))
      session.put("citizenReference", jwt.getToken().getSubject());
    return session;
  }
}
