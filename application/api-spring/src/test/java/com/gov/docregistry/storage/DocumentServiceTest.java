package com.gov.docregistry.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.ipfs.IpfsService;
import com.gov.docregistry.web.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

class DocumentServiceTest {
  @TempDir Path temp;
  ObjectMapper mapper = new ObjectMapper();
  DocumentLedger ledger;
  IpfsService ipfs;
  DocumentCrypto crypto;
  UploadJournal journal;
  DocumentService service;
  JdbcTemplate jdbc;
  final byte[] file = "%PDF-1.4 synthetic certificate".getBytes();
  final String cid = "bafy" + "a".repeat(50);

  @BeforeEach
  void setup() throws Exception {
    Path keys = temp.resolve("keys.json");
    Files.writeString(
        keys,
        "{\"activeKeyId\":\"test\",\"keys\":{\"test\":\""
            + Base64.getEncoder().encodeToString(new byte[32])
            + "\"}}");
    crypto = new DocumentCrypto(mapper, keys);
    ledger = mock(DocumentLedger.class);
    ipfs = mock(IpfsService.class);
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource("jdbc:h2:file:" + temp.resolve("journal"), "sa", ""));
    journal = new UploadJournal(jdbc);
    service =
        new DocumentService(
            ledger, ipfs, crypto, journal, mapper, "Org1", 10485760, "https://identity.example");
    when(ledger.get(anyString()))
        .thenThrow(new RegistryException("NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found"));
    when(ipfs.addBytes(any(), anyString())).thenReturn(cid);
    when(ledger.register(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(a -> record(a.getArgument(0), a.getArgument(1), a.getArgument(4)));
  }

  ObjectNode record(String id, String hash, String metadata) throws Exception {
    ObjectNode n =
        mapper
            .createObjectNode()
            .put("docId", id)
            .put("hash", hash)
            .put("revoked", false)
            .put("issuer", "Org1")
            .put("subject", "");
    n.set("metadata", mapper.readTree(metadata));
    return n;
  }

  MockMultipartFile upload() {
    return new MockMultipartFile("file", "quoted\"name.pdf", "application/pdf", file);
  }

  @Test
  void expiryIsStoredAndBlocksDownloadBeforeStorageRead() throws Exception {
    JsonNode result = service.upload("EXP-001", "", "", "", null, "2020-01-01T00:00:00.000Z", upload());
    assertEquals("2020-01-01T00:00:00.000Z", result.path("metadata").path("expiresAt").asText());
    doReturn(result).when(ledger).get("EXP-001");
    assertEquals("EXPIRED", assertThrows(RegistryException.class, () -> service.download("EXP-001")).code());
    verify(ipfs, never()).getBytes(anyString());
    assertTrue(DocumentService.isExpired(result));
  }

  @Test
  void invalidExpiryRejectedBeforeStorageOrJournalAndRetryBindsExpiry() throws Exception {
    for (String expiry : List.of("2026-02-30T00:00:00.000Z", "not-a-date", "2026-01-01T00:00:00Z"))
      assertThrows(RegistryException.class, () -> service.upload("EXP-001", "", "", "", null, expiry, upload()));
    verifyNoInteractions(ipfs);
    assertTrue(journal.find("EXP-001").isEmpty());
    service.upload("EXP-001", "", "", "", null, "2099-01-01T00:00:00.000Z", upload());
    assertEquals("DUPLICATE", assertThrows(RegistryException.class, () -> service.upload("EXP-001", "", "", "", null, "2099-02-01T00:00:00.000Z", upload())).code());
  }

  @Test
  void encryptsBeforeIpfsAndStoresSafeMetadata() throws Exception {
    JsonNode result = service.upload("DOC-001", "", "opaque", "type\"quote", upload());
    assertEquals(cid, result.path("metadata").path("ipfsCid").asText());
    assertEquals(DocumentCrypto.hash(file), result.path("hash").asText());
    assertFalse(result.path("metadata").has("fileName"));
    assertEquals("type\"quote", result.path("metadata").path("type").asText());
    var bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(ipfs).addBytes(bytes.capture(), anyString());
    assertFalse(Arrays.equals(file, bytes.getValue()));
    assertArrayEquals(file, crypto.decrypt("DOC-001", "test", bytes.getValue()));
    assertEquals("CONFIRMED", journal.find("DOC-001").orElseThrow().state());
    assertNull(journal.find("DOC-001").orElseThrow().ciphertext());
  }

  @Test
  void uncertainCommitIsReconciledWithoutReuploadOrDuplicateSubmit() throws Exception {
    final JsonNode[] committed = {null};
    when(ledger.register(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            a -> {
              committed[0] = record(a.getArgument(0), a.getArgument(1), a.getArgument(4));
              throw RegistryException.unavailable();
            });
    RegistryException pending =
        assertThrows(
            RegistryException.class, () -> service.upload("DOC-001", "", "", "", upload()));
    assertEquals("REGISTRATION_PENDING", pending.code());
    // Reopen persistent storage as if the process restarted.
    UploadJournal reopened = new UploadJournal(jdbc);
    DocumentService restarted =
        new DocumentService(
            ledger, ipfs, crypto, reopened, mapper, "Org1", 10485760, "https://identity.example");
    doReturn(committed[0]).when(ledger).get("DOC-001");
    assertEquals("DOC-001", restarted.advance("DOC-001").path("docId").asText());
    verify(ipfs, times(1)).addBytes(any(), anyString());
    verify(ledger, times(1))
        .register(anyString(), anyString(), anyString(), anyString(), anyString());
    assertNull(reopened.find("DOC-001").orElseThrow().ciphertext());
  }

  @Test
  void ipfsFailureLeavesEncryptedDurableJobAndCanRetry() {
    when(ipfs.addBytes(any(), anyString()))
        .thenThrow(RegistryException.unavailable())
        .thenReturn(cid);
    assertEquals(
        "REGISTRATION_PENDING",
        assertThrows(RegistryException.class, () -> service.upload("DOC-001", "", "", "", upload()))
            .code());
    UploadJournal.Job job = journal.find("DOC-001").orElseThrow();
    assertNotNull(job.ciphertext());
    assertEquals("STAGING", job.state());
    assertEquals("DOC-001", service.advance("DOC-001").path("docId").asText());
  }

  @Test
  void duplicateExistingDocumentIsDetectedBeforeUpload() {
    doReturn(mapper.createObjectNode().put("docId", "DOC-001")).when(ledger).get("DOC-001");
    assertEquals(
        "DUPLICATE",
        assertThrows(RegistryException.class, () -> service.upload("DOC-001", "", "", "", upload()))
            .code());
    verifyNoInteractions(ipfs);
  }

  @Test
  void retryWithDifferentContentCannotReuseJournalEntry() {
    when(ipfs.addBytes(any(), anyString())).thenThrow(RegistryException.unavailable());
    assertThrows(RegistryException.class, () -> service.upload("DOC-001", "", "", "", upload()));
    var other =
        new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4 changed".getBytes());
    assertEquals(
        "DUPLICATE",
        assertThrows(RegistryException.class, () -> service.upload("DOC-001", "", "", "", other))
            .code());
  }

  @Test
  void downloadAuthenticatesCiphertextAndChecksPlaintextHash() throws Exception {
    JsonNode record = service.upload("DOC-001", "", "", "", upload());
    doReturn(record).when(ledger).get("DOC-001");
    when(ipfs.getBytes(cid)).thenReturn(crypto.encrypt("DOC-001", file));
    assertArrayEquals(file, service.download("DOC-001").bytes());
    when(ipfs.getBytes(cid)).thenReturn(crypto.encrypt("DOC-001", "%PDF-different".getBytes()));
    assertEquals(
        "INTEGRITY_ERROR",
        assertThrows(RegistryException.class, () -> service.download("DOC-001")).code());
  }

  @Test
  void revokedDownloadDoesNotFetchContent() throws Exception {
    doReturn(mapper.createObjectNode().put("revoked", true)).when(ledger).get("DOC-001");
    assertEquals(
        "REVOKED", assertThrows(RegistryException.class, () -> service.download("DOC-001")).code());
    verifyNoInteractions(ipfs);
  }

  @Test
  void encryptionRejectsAlterationWrongDocumentAndUnknownKey() {
    byte[] encrypted = crypto.encrypt("DOC-001", file);
    encrypted[encrypted.length - 1] ^= 1;
    assertThrows(RegistryException.class, () -> crypto.decrypt("DOC-001", "test", encrypted));
    byte[] original = crypto.encrypt("DOC-001", file);
    assertThrows(RegistryException.class, () -> crypto.decrypt("DOC-002", "test", original));
    assertThrows(RegistryException.class, () -> crypto.decrypt("DOC-001", "missing", original));
  }

  @Test
  void duplicateMetadataFieldsAndReservedStorageFieldsRejected() {
    for (String metadata : List.of("{\"x\":1,\"x\":2}", "{\"ipfsCid\":\"fake\"}", "null", "{} {}"))
      assertThrows(
          RegistryException.class,
          () ->
              service.register(
                  new Requests.Register("DOC-001", DocumentCrypto.hash(file), "", "", metadata)));
    verifyNoInteractions(ledger);
  }

  @Test
  void emptyAndOversizedFilesRejected() {
    assertThrows(
        RegistryException.class,
        () -> service.upload("DOC-001", "", "", "", new MockMultipartFile("file", new byte[0])));
    var large = new MockMultipartFile("file", new byte[10485761]);
    assertEquals(
        "FILE_TOO_LARGE",
        assertThrows(RegistryException.class, () -> service.upload("DOC-001", "", "", "", large))
            .code());
    verifyNoInteractions(ipfs, ledger);
  }

  @Test
  void issuerAssignsHashedOwnerAndOwnershipCannotChangeOnRetry() {
    var result = service.upload("DOC-001", "", "", "", "citizen-id", upload());
    assertEquals(
        com.gov.docregistry.security.DocumentAccess.ownerHash(
            "https://identity.example", "citizen-id"),
        result.path("metadata").path("ownerSubjectHash").asText());
    assertFalse(result.toString().contains("citizen-id"));
    assertEquals(
        "DUPLICATE",
        assertThrows(
                RegistryException.class,
                () -> service.upload("DOC-001", "", "", "", "another-citizen", upload()))
            .code());
  }
}
