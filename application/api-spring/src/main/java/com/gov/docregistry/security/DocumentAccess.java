package com.gov.docregistry.security;

import com.gov.docregistry.storage.DocumentCrypto;
import com.gov.docregistry.web.RegistryException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class DocumentAccess {
  private final String department, msp;

  public DocumentAccess(
      @Value("${registry.issuer:Org1}") String department,
      @Value("${registry.fabric.msp:Org1MSP}") String msp) {
    this.department = department;
    this.msp = msp;
  }

  public static String ownerHash(String issuer, String subject) {
    if (issuer == null
        || !issuer.startsWith("https://")
        || subject == null
        || subject.isBlank()
        || subject.length() > 256)
      throw RegistryException.validation(
          "Citizen ownership requires a configured HTTPS identity issuer and a valid subject"
              + " identifier");
    return DocumentCrypto.hash((issuer + "\u0000" + subject).getBytes(StandardCharsets.UTF_8));
  }

  public String searchOwner() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_READER"))) return "";
    if (auth instanceof JwtAuthenticationToken jwt
        && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CITIZEN")))
      return ownerHash(jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
    throw RegistryException.forbidden();
  }

  public void requireRead(JsonNode record) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    boolean sameDepartment =
        department.equals(record.path("issuer").asText())
            && msp.equals(record.path("issuerMspId").asText());
    if (auth != null && sameDepartment) {
      if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_READER")))
        return;
      if (auth instanceof JwtAuthenticationToken jwt
          && auth.getAuthorities().stream()
              .anyMatch(a -> a.getAuthority().equals("ROLE_CITIZEN"))) {
        String owner =
            ownerHash(jwt.getToken().getIssuer().toString(), jwt.getToken().getSubject());
        if (owner.equals(record.path("metadata").path("ownerSubjectHash").asText())) return;
      }
    }
    // Do not distinguish a missing document from one owned by someone else.
    throw new RegistryException("NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found");
  }
}
