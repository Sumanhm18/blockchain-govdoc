package com.gov.docregistry.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtClaimsTest {
  private Jwt token(String issuer, String audience, String department, Instant expiry) {
    return Jwt.withTokenValue("synthetic")
        .header("alg", "RS256")
        .issuer(issuer)
        .audience(List.of(audience))
        .claim("department", department)
        .issuedAt(Instant.now().minusSeconds(600))
        .expiresAt(expiry)
        .build();
  }

  @Test
  void requiresIssuerAudienceDepartmentAndUnexpiredToken() {
    var validator = SecurityConfig.tokenValidator("https://id.example", "govdoc-api", "Org1");
    Instant future = Instant.now().plusSeconds(600);
    assertFalse(
        validator.validate(token("https://id.example", "govdoc-api", "Org1", future)).hasErrors());
    assertTrue(
        validator
            .validate(token("https://other.example", "govdoc-api", "Org1", future))
            .hasErrors());
    assertTrue(
        validator.validate(token("https://id.example", "other", "Org1", future)).hasErrors());
    assertTrue(
        validator.validate(token("https://id.example", "govdoc-api", "Org2", future)).hasErrors());
    assertTrue(
        validator
            .validate(
                token("https://id.example", "govdoc-api", "Org1", Instant.now().minusSeconds(300)))
            .hasErrors());
  }
}
