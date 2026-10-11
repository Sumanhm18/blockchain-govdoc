package com.gov.docregistry.web;

import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.storage.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@Validated
@RequestMapping(value = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentController {
  private final DocumentLedger ledger;
  private final DocumentService documents;
  private final com.gov.docregistry.security.DocumentAccess access;

  public DocumentController(
      DocumentLedger ledger,
      DocumentService documents,
      com.gov.docregistry.security.DocumentAccess access) {
    this.ledger = ledger;
    this.documents = documents;
    this.access = access;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ISSUER')")
  public JsonNode register(@Valid @RequestBody Requests.Register request) {
    return documents.register(request);
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ISSUER')")
  public JsonNode upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam("docId") @Pattern(regexp = Requests.ID) String docId,
      @RequestParam(value = "issuer", defaultValue = "") @Size(max = 128) String issuer,
      @RequestParam(value = "subject", defaultValue = "") @Size(max = 128) String subject,
      @RequestParam(value = "type", defaultValue = "") @Size(max = 64) String type,
      @RequestParam(value = "ownerSubject", defaultValue = "") @Size(max = 256)
          String ownerSubject) {
    return documents.upload(docId, issuer, subject, type, ownerSubject, file);
  }

  @GetMapping("/{docId}")
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public JsonNode get(@PathVariable @Pattern(regexp = Requests.ID) String docId) {
    JsonNode record = ledger.get(docId);
    access.requireRead(record);
    return record;
  }

  @PostMapping(value = "/{docId}/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public JsonNode verify(
      @PathVariable @Pattern(regexp = Requests.ID) String docId,
      @Valid @RequestBody Requests.Verify request) {
    access.requireRead(ledger.get(docId));
    return ledger.verify(docId, request.hash());
  }

  @PostMapping(value = "/{docId}/verify-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public JsonNode verifyFile(
      @PathVariable @Pattern(regexp = Requests.ID) String docId,
      @RequestPart("file") MultipartFile file) {
    access.requireRead(ledger.get(docId));
    return documents.verifyFile(docId, file);
  }

  @PostMapping(value = "/{docId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('REVOKER')")
  public JsonNode revoke(
      @PathVariable @Pattern(regexp = Requests.ID) String docId,
      @Valid @RequestBody Requests.Revoke request) {
    return ledger.revoke(docId, request.reason());
  }

  @GetMapping("/{docId}/history")
  @PreAuthorize("hasRole('READER')")
  public JsonNode history(@PathVariable @Pattern(regexp = Requests.ID) String docId) {
    access.requireRead(ledger.get(docId));
    return ledger.history(docId);
  }

  @GetMapping("/{docId}/registration")
  @PreAuthorize("hasRole('ISSUER')")
  public Map<String, Object> registration(
      @PathVariable @Pattern(regexp = Requests.ID) String docId) {
    return documents.registrationStatus(docId);
  }

  @GetMapping(value = "/{docId}/file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  @PreAuthorize("hasAnyRole('READER','CITIZEN')")
  public ResponseEntity<byte[]> download(
      @PathVariable @Pattern(regexp = Requests.ID) String docId) {
    access.requireRead(ledger.get(docId));
    DocumentService.Download file = documents.download(docId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.mediaType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + docId + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(file.bytes());
  }
}
