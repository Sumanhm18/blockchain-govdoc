package com.gov.docregistry.web;

import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.security.DocumentAccess;
import com.gov.docregistry.storage.DocumentCrypto;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@Validated
public class DocumentSearchController {
  private final DocumentLedger ledger;
  private final DocumentAccess access;
  private final DocumentCrypto crypto;
  private final ObjectMapper mapper;
  public DocumentSearchController(DocumentLedger ledger, DocumentAccess access, DocumentCrypto crypto, ObjectMapper mapper) {
    this.ledger = ledger; this.access = access; this.crypto = crypto; this.mapper = mapper;
  }
  @GetMapping("/api/documents/summary")
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public tools.jackson.databind.JsonNode summary() {
    var result = ledger.summary(access.searchOwner());
    for (var record : result.path("recent")) access.requireRead(record);
    for (var record : result.path("alerts").path("items")) access.requireRead(record);
    return result;
  }
  @GetMapping("/api/documents")
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public Map<String, Object> search(
      @RequestParam(defaultValue = "") @Pattern(regexp = "[A-Z0-9-]{0,50}") String prefix,
      @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|ACTIVE|REVOKED|EXPIRED") String status,
      @RequestParam(defaultValue = "") @Size(max = 8192) String cursor) {
    String owner = access.searchOwner();
    String binding = prefix + "\u0000" + status + "\u0000" + owner;
    String after = "";
    if (!cursor.isEmpty()) {
      try {
        String[] parts = cursor.split("\\.", 2);
        var data = mapper.readTree(crypto.decrypt("registry-search-cursor-v1", parts[0], Base64.getUrlDecoder().decode(parts[1])));
        if (!binding.equals(data.path("binding").asText()) || data.path("expires").asLong() < Instant.now().getEpochSecond())
          throw new IllegalArgumentException();
        after = data.path("after").asText();
      } catch (Exception e) { throw RegistryException.validation("Search cursor is invalid or expired; start a new search"); }
    }
    var result = ledger.search(prefix, status, owner, after);
    var items = mapper.createArrayNode();
    for (var record : result.path("items")) { access.requireRead(record); items.add(record); }
    String next = result.path("nextAfter").asText();
    String token = "";
    if (!next.isEmpty()) {
      byte[] data = mapper.writeValueAsString(Map.of("after", next, "binding", binding, "expires", Instant.now().getEpochSecond() + 900)).getBytes(StandardCharsets.UTF_8);
      token = crypto.activeKeyId() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.encrypt("registry-search-cursor-v1", data));
    }
    return Map.of("items", items, "nextCursor", token);
  }
}
