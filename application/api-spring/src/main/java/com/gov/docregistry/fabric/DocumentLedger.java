package com.gov.docregistry.fabric;

import tools.jackson.databind.JsonNode;

public interface DocumentLedger {
  JsonNode register(String docId, String hash, String issuer, String subject, String metadataJson);

  JsonNode get(String docId);

  JsonNode verify(String docId, String hash);

  JsonNode revoke(String docId, String reason);

  JsonNode history(String docId);

  JsonNode search(String prefix, String status, String owner, String after);

  JsonNode summary(String owner);

  void ping();
}
