package com.gov.docregistry.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.ipfs.IpfsService;
import com.gov.docregistry.web.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  private final DocumentLedger ledger;
  private final IpfsService ipfs;
  private final DocumentCrypto crypto;
  private final UploadJournal journal;
  private final ObjectMapper mapper;
  private final String issuer;
  private final int maxBytes;
  private final String ownershipIssuer;

  public DocumentService(
      DocumentLedger ledger,
      IpfsService ipfs,
      DocumentCrypto crypto,
      UploadJournal journal,
      ObjectMapper mapper,
      @Value("${registry.issuer:Org1}") String issuer,
      @Value("${registry.upload.max-bytes:10485760}") int maxBytes,
      @Value("${registry.auth.issuer-uri:}") String ownershipIssuer) {
    this.ledger = ledger;
    this.ipfs = ipfs;
    this.crypto = crypto;
    this.journal = journal;
    this.mapper = mapper;
    this.issuer = issuer;
    this.maxBytes = maxBytes;
    this.ownershipIssuer = ownershipIssuer;
  }

  public JsonNode register(Requests.Register request) {
    checkIssuer(request.issuer());
    ObjectNode metadata = metadata(request.metadataJson() == null ? "{}" : request.metadataJson());
    for (String key : List.of("registrationId", "encryption", "ipfsCid", "ownerSubjectHash"))
      if (metadata.has(key))
        throw RegistryException.validation("Storage metadata is managed by the upload endpoint");
    addOwner(metadata, request.ownerSubject());
    return ledger.register(
        request.docId(), request.hash(), issuer, orEmpty(request.subject()), metadata.toString());
  }

  public synchronized JsonNode upload(
      String id, String requestedIssuer, String subject, String type, MultipartFile file) {
    return upload(id, requestedIssuer, subject, type, null, file);
  }

  public synchronized JsonNode upload(
      String id,
      String requestedIssuer,
      String subject,
      String type,
      String ownerSubject,
      MultipartFile file) {
    checkIssuer(requestedIssuer);
    byte[] bytes = bytes(file);
    String mediaType = mediaType(bytes);
    ObjectNode request =
        mapper
            .createObjectNode()
            .put("docId", id)
            .put("hash", DocumentCrypto.hash(bytes))
            .put("issuer", issuer)
            .put("subject", orEmpty(subject));
    ObjectNode meta = request.putObject("metadata");
    meta.put("type", orEmpty(type));
    meta.put("mediaType", mediaType);
    addOwner(meta, ownerSubject);
    String fingerprint = DocumentCrypto.hash(request.toString().getBytes(StandardCharsets.UTF_8));
    Optional<UploadJournal.Job> prior = journal.find(id);
    if (prior.isPresent()) {
      if (!prior.get().fingerprint().equals(fingerprint)) throw duplicate();
      if (prior.get().state().equals("REJECTED")) throw rejected(prior.get().code());
      return advance(id);
    }
    String registrationId = UUID.randomUUID().toString();
    meta.put("registrationId", registrationId);
    meta.putObject("encryption")
        .put("algorithm", "AES-256-GCM")
        .put("keyId", crypto.activeKeyId())
        .put("format", "GOVDOC01");
    journal.create(
        new UploadJournal.Job(
            id,
            fingerprint,
            registrationId,
            request.toString(),
            crypto.encrypt(id, bytes),
            "STAGING",
            0,
            "",
            System.currentTimeMillis()));
    return advance(id);
  }

  public synchronized JsonNode advance(String id) {
    UploadJournal.Job job = journal.find(id).orElseThrow();
    if (job.state().equals("REJECTED")) throw rejected(job.code());
    ObjectNode request = metadata(job.request());
    try {
      JsonNode existing = null;
      try {
        existing = ledger.get(id);
      } catch (RegistryException e) {
        if (!e.code().equals("NOT_FOUND")) throw e;
      }
      if (existing != null) {
        if (!job.registrationId().equals(existing.path("metadata").path("registrationId").asText())
            || !request.path("hash").equals(existing.path("hash"))
            || !request.path("issuer").equals(existing.path("issuer"))
            || !request.path("subject").equals(existing.path("subject"))
            || !request.path("metadata").equals(existing.path("metadata"))) {
          journal.update(id, "REJECTED", request.toString(), "DUPLICATE", false);
          throw duplicate();
        }
        journal.update(id, "CONFIRMED", request.toString(), "", true);
        return existing;
      }
      if (job.state().equals("CONFIRMED")) throw RegistryException.unavailable();
      ObjectNode meta = (ObjectNode) request.get("metadata");
      if (!meta.hasNonNull("ipfsCid")) {
        // Ciphertext is durable before upload. Retrying after a crash produces the same CID.
        String cid = ipfs.addBytes(job.ciphertext(), "encrypted-document.bin");
        meta.put("ipfsCid", cid);
        journal.update(id, "PINNED", request.toString(), "", false);
      }
      journal.update(id, "SUBMITTING", request.toString(), "", false);
      JsonNode registered =
          ledger.register(
              id,
              request.path("hash").asText(),
              request.path("issuer").asText(),
              request.path("subject").asText(),
              meta.toString());
      journal.update(id, "CONFIRMED", request.toString(), "", true);
      return registered;
    } catch (RegistryException e) {
      if (Set.of("FORBIDDEN", "VALIDATION").contains(e.code())) {
        journal.update(id, "REJECTED", request.toString(), e.code(), false);
        throw e;
      }
      if (e.code().equals("DUPLICATE") && journal.find(id).orElseThrow().state().equals("REJECTED"))
        throw e;
      // A timeout or a concurrent duplicate is not proof that submission failed. Query before
      // retrying.
      journal.defer(id, e.code());
      throw new RegistryException(
          "REGISTRATION_PENDING",
          HttpStatus.ACCEPTED,
          "Registration is being reconciled; check its registration status");
    }
  }

  public synchronized Map<String, Object> registrationStatus(String id) {
    UploadJournal.Job j =
        journal
            .find(id)
            .orElseThrow(
                () ->
                    new RegistryException(
                        "NOT_FOUND", HttpStatus.NOT_FOUND, "Registration attempt not found"));
    return Map.of(
        "docId",
        id,
        "state",
        j.state(),
        "code",
        j.code(),
        "attempts",
        j.attempts(),
        "updatedAt",
        j.updatedAt());
  }

  public JsonNode verifyFile(String id, MultipartFile file) {
    return ledger.verify(id, DocumentCrypto.hash(bytes(file)));
  }

  public record Download(byte[] bytes, String mediaType) {}

  public Download download(String id) {
    JsonNode record = ledger.get(id);
    if (record.path("revoked").asBoolean())
      throw new RegistryException("REVOKED", HttpStatus.CONFLICT, "Document has been revoked");
    JsonNode meta = record.path("metadata");
    JsonNode encryption = meta.path("encryption");
    if (!"AES-256-GCM".equals(encryption.path("algorithm").asText())
        || !"GOVDOC01".equals(encryption.path("format").asText()))
      throw new RegistryException(
          "UNSUPPORTED_STORAGE",
          HttpStatus.CONFLICT,
          "Document requires a reviewed storage migration");
    byte[] bytes =
        crypto.decrypt(
            id, encryption.path("keyId").asText(), ipfs.getBytes(meta.path("ipfsCid").asText()));
    if (!DocumentCrypto.hash(bytes).equals(record.path("hash").asText()))
      throw new RegistryException(
          "INTEGRITY_ERROR",
          HttpStatus.BAD_GATEWAY,
          "Stored document does not match its registered hash");
    return new Download(bytes, mediaType(bytes));
  }

  private byte[] bytes(MultipartFile file) {
    if (file == null || file.isEmpty())
      throw RegistryException.validation("A nonempty file is required");
    if (file.getSize() > maxBytes)
      throw new RegistryException(
          "FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the configured limit");
    try (var stream = file.getInputStream()) {
      byte[] bytes = stream.readNBytes(maxBytes + 1);
      if (bytes.length > maxBytes)
        throw new RegistryException(
            "FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the configured limit");
      return bytes;
    } catch (java.io.IOException e) {
      throw RegistryException.validation("File could not be read");
    }
  }

  private String mediaType(byte[] bytes) {
    if (bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
      return "application/pdf";
    if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "image/png";
    if (bytes.length >= 3
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255) return "image/jpeg";
    throw RegistryException.validation("Only PDF, PNG and JPEG signatures are accepted");
  }

  private ObjectNode metadata(String json) {
    try (JsonParser parser = mapper.getFactory().createParser(json)) {
      parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      JsonNode node = mapper.readTree(parser);
      if (!(node instanceof ObjectNode object) || parser.nextToken() != null)
        throw RegistryException.validation("Metadata must be one JSON object");
      return object;
    } catch (java.io.IOException e) {
      throw RegistryException.validation("Metadata must be valid JSON without duplicate fields");
    }
  }

  private void addOwner(ObjectNode metadata, String subject) {
    if (subject != null && !subject.isBlank())
      metadata.put(
          "ownerSubjectHash",
          com.gov.docregistry.security.DocumentAccess.ownerHash(ownershipIssuer, subject));
  }

  private void checkIssuer(String value) {
    if (value != null && !value.isBlank() && !issuer.equals(value))
      throw RegistryException.forbidden();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private RegistryException duplicate() {
    return new RegistryException(
        "DUPLICATE", HttpStatus.CONFLICT, "Document ID is already used by another registration");
  }

  private RegistryException rejected(String code) {
    return code.equals("FORBIDDEN")
        ? RegistryException.forbidden()
        : code.equals("DUPLICATE")
            ? duplicate()
            : RegistryException.validation(
                "Registration was rejected; operator review is required");
  }
}
