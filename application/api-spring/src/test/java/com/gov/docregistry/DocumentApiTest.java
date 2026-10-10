package com.gov.docregistry;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.ipfs.IpfsService;
import com.gov.docregistry.web.RegistryException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentApiTest {
  static final String HASH = "sha256:" + "a".repeat(64);
  static final Path temp;

  static {
    try {
      temp = Files.createTempDirectory("govdoc-test-");
      String hash =
          Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("test-only-passphrase");
      Files.writeString(
          temp.resolve("users.json"),
          "[{\"username\":\"test-reader\",\"passwordHash\":\""
              + hash
              + "\",\"roles\":[\"READER\"]}]");
      Files.writeString(
          temp.resolve("keys.json"),
          "{\"activeKeyId\":\"test\",\"keys\":{\"test\":\""
              + Base64.getEncoder().encodeToString(new byte[32])
              + "\"}}");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("registry.auth.users-file", () -> temp.resolve("users.json").toString());
    r.add("registry.encryption.keys-file", () -> temp.resolve("keys.json").toString());
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:api-tests;DB_CLOSE_DELAY=-1");
    r.add("registry.reconcile.delay-ms", () -> "86400000");
    r.add("registry.rate-limit.per-minute", () -> "1000");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean DocumentLedger ledger;
  @MockitoBean IpfsService ipfs;

  @BeforeEach
  void resetJournal() {
    jdbc.execute("DELETE FROM upload_jobs");
  }

  @Test
  void anonymousDocumentAccessIsDenied() throws Exception {
    mvc.perform(get("/api/documents/DOC-001")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/documents/DOC-001/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"test\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(ledger);
  }

  @Test
  void configuredBasicCredentialsAuthenticate() throws Exception {
    when(ledger.get("DOC-001")).thenReturn(mapper.createObjectNode().put("docId", "DOC-001"));
    mvc.perform(
            get("/api/documents/DOC-001").with(httpBasic("test-reader", "test-only-passphrase")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/documents/DOC-001").with(httpBasic("test-reader", "wrong")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "READER")
  void readerCannotRegisterOrRevoke() throws Exception {
    mvc.perform(
            post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("DOC-001", HASH)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/documents/DOC-001/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"test\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(ledger);
  }

  @Test
  @WithMockUser(roles = "ISSUER")
  void registrationPreservesRouteAndJsonResponse() throws Exception {
    when(ledger.register(eq("DOC-001"), eq(HASH), eq("Org1"), eq(""), eq("{}")))
        .thenReturn(mapper.createObjectNode().put("docId", "DOC-001"));
    mvc.perform(
            post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("DOC-001", HASH)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.docId").value("DOC-001"));
  }

  @Test
  @WithMockUser(roles = "ISSUER")
  void invalidIdsAndHashesReturn400BeforeLedgerCall() throws Exception {
    mvc.perform(
            post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("bad id", HASH)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("DOC-001", "fake")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(ledger);
  }

  @Test
  @WithMockUser(roles = "ISSUER")
  void issuerSpoofingIsForbidden() throws Exception {
    String body =
        mapper
            .createObjectNode()
            .put("docId", "DOC-001")
            .put("hash", HASH)
            .put("issuer", "OtherDepartment")
            .toString();
    mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    verifyNoInteractions(ledger);
  }

  @Test
  @WithMockUser(roles = "ISSUER")
  void duplicateAndDependencyFailureHaveDifferentStatusCodes() throws Exception {
    when(ledger.register(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(
            new RegistryException("DUPLICATE", HttpStatus.CONFLICT, "Document already exists"))
        .thenThrow(RegistryException.unavailable());
    var body = registerBody("DOC-001", HASH);
    mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  @WithMockUser(roles = "READER")
  void nonexistentRecordReturns404() throws Exception {
    when(ledger.get("MISSING"))
        .thenThrow(new RegistryException("NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found"));
    mvc.perform(get("/api/documents/MISSING"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @WithMockUser(roles = "READER")
  void fileVerificationHashesActualBytes() throws Exception {
    byte[] bytes = "%PDF-1.4 test".getBytes();
    when(ledger.verify(eq("DOC-001"), anyString()))
        .thenReturn(mapper.createObjectNode().put("valid", false).put("status", "REVOKED"));
    mvc.perform(
            multipart("/api/documents/DOC-001/verify-file")
                .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", bytes)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false));
    verify(ledger).verify("DOC-001", com.gov.docregistry.storage.DocumentCrypto.hash(bytes));
  }

  @Test
  @WithMockUser(roles = "ISSUER")
  void unsupportedUploadRejectedBeforeIpfs() throws Exception {
    mvc.perform(
            multipart("/api/documents/upload")
                .file(
                    new MockMultipartFile(
                        "file", "x.pdf", "application/pdf", "not a pdf".getBytes()))
                .param("docId", "DOC-001"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(ipfs, ledger);
  }

  @Test
  void dependencyFailureMakesReadiness503WithoutDetails() throws Exception {
    doThrow(RegistryException.unavailable()).when(ledger).ping();
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "READER")
  void internalExceptionDetailsAreNotReturned() throws Exception {
    when(ledger.get("DOC-001")).thenThrow(new IllegalStateException("sensitive-private-path"));
    mvc.perform(get("/api/documents/DOC-001"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-private-path"))));
  }

  @Test
  @WithMockUser(roles = "REVOKER")
  void authorizedRevokeRequiresReason() throws Exception {
    when(ledger.revoke("DOC-001", "expired"))
        .thenReturn(mapper.createObjectNode().put("revoked", true));
    mvc.perform(
            post("/api/documents/DOC-001/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"expired\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revoked").value(true));
    mvc.perform(
            post("/api/documents/DOC-001/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  private String registerBody(String id, String hash) {
    return mapper.createObjectNode().put("docId", id).put("hash", hash).toString();
  }
}
