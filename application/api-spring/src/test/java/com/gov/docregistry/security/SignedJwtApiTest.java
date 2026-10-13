package com.gov.docregistry.security;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.ipfs.IpfsService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "registry.auth.mode=jwt",
      "registry.auth.issuer-uri=https://identity.example",
      "registry.auth.require-https=true",
      "spring.datasource.url=jdbc:h2:mem:jwt-tests;DB_CLOSE_DELAY=-1",
      "registry.reconcile.delay-ms=86400000"
    })
@AutoConfigureMockMvc
class SignedJwtApiTest {
  static final RSAKey key;
  static final Path keys;

  static {
    try {
      key = new RSAKeyGenerator(2048).keyID("test-only").generate();
      keys = Files.createTempFile("registry-jwt-keys-", ".json");
      Files.writeString(
          keys,
          "{\"activeKeyId\":\"test\",\"keys\":{\"test\":\""
              + Base64.getEncoder().encodeToString(new byte[32])
              + "\"}}");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("registry.encryption.keys-file", () -> keys.toString());
  }

  @Test
  void summaryDerivesOwnerAndChecksRecentRecords() throws Exception {
    var result = new ObjectMapper().createObjectNode(); result.putArray("recent"); result.putObject("counts").put("total", 0);
    String owner = DocumentAccess.ownerHash("https://identity.example", "opaque-citizen-or-staff-id");
    when(ledger.summary(owner)).thenReturn(result);
    mvc.perform(get("/api/documents/summary").with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.counts.total").value(0));
    verify(ledger).summary(owner);
    result.withArray("recent").addObject().put("docId", "FOREIGN").put("issuer", "Other");
    mvc.perform(get("/api/documents/summary").with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/documents/summary").with(https())).andExpect(status().isUnauthorized());
  }

  @TestBean(enforceOverride = true)
  JwtDecoder jwtDecoder;

  static JwtDecoder jwtDecoder() throws Exception {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(
        SecurityConfig.tokenValidator("https://identity.example", "govdoc-api", "Org1"));
    return decoder;
  }

  @Autowired MockMvc mvc;
  @MockitoBean DocumentLedger ledger;
  @MockitoBean IpfsService ipfs;

  String token(
      RSAKey signer,
      String issuer,
      String audience,
      String department,
      List<String> roles,
      Instant expires)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .subject("opaque-citizen-or-staff-id")
                .issuer(issuer)
                .audience(audience)
                .claim("department", department)
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expires))
                .build());
    jwt.sign(new RSASSASigner(signer));
    return jwt.serialize();
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor https() {
    return request -> {
      request.setSecure(true);
      request.setScheme("https");
      return request;
    };
  }

  String valid(List<String> roles) throws Exception {
    return token(
        key,
        "https://identity.example",
        "govdoc-api",
        "Org1",
        roles,
        Instant.now().plusSeconds(300));
  }

  @Test
  void sessionReturnsOnlyAuthenticatedCitizensOwnReference() throws Exception {
    mvc.perform(get("/api/session").with(https()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/session").with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.citizenReference").value("opaque-citizen-or-staff-id"));
    mvc.perform(get("/api/session").with(https()).header("Authorization", "Bearer " + valid(List.of("READER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.citizenReference").doesNotExist());
  }

  @Test
  void signedReaderTokenAuthenticatesAndCannotRevoke() throws Exception {
    when(ledger.get("DOC-001"))
        .thenReturn(
            new ObjectMapper()
                .createObjectNode()
                .put("docId", "DOC-001")
                .put("issuer", "Org1")
                .put("issuerMspId", "Org1MSP"));
    String bearer = "Bearer " + valid(List.of("READER"));
    mvc.perform(get("/api/documents/DOC-001").with(https()).header("Authorization", bearer))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/documents/DOC-001/revoke")
                .with(https())
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"reason\":\"test\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsForgedExpiredWrongIssuerAudienceAndDepartment() throws Exception {
    Instant future = Instant.now().plusSeconds(300);
    List<String> tokens =
        List.of(
            token(
                new RSAKeyGenerator(2048).generate(),
                "https://identity.example",
                "govdoc-api",
                "Org1",
                List.of("READER"),
                future),
            token(key, "https://other.example", "govdoc-api", "Org1", List.of("READER"), future),
            token(key, "https://identity.example", "other", "Org1", List.of("READER"), future),
            token(key, "https://identity.example", "govdoc-api", "Org2", List.of("READER"), future),
            token(
                key,
                "https://identity.example",
                "govdoc-api",
                "Org1",
                List.of("READER"),
                Instant.now().minusSeconds(300)));
    for (String jwt : tokens)
      mvc.perform(
              get("/api/documents/DOC-001").with(https()).header("Authorization", "Bearer " + jwt))
          .andExpect(status().isUnauthorized());
    verifyNoInteractions(ledger);
  }

  @Test
  void validTokenWithoutReadRoleIsDenied() throws Exception {
    mvc.perform(
            get("/api/documents/DOC-001")
                .with(https())
                .header("Authorization", "Bearer " + valid(List.of("OPERATOR"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(ledger);
  }

  @Test
  void plaintextTransportDoesNotServeDocuments() throws Exception {
    mvc.perform(
            get("/api/documents/DOC-001")
                .header("Authorization", "Bearer " + valid(List.of("READER"))))
        .andExpect(status().is3xxRedirection());
    verifyNoInteractions(ledger);
  }

  @Test
  void citizenCanReadOwnRecordButCannotReadAnotherOrAccessHistory() throws Exception {
    var own =
        new ObjectMapper()
            .createObjectNode()
            .put("docId", "DOC-001")
            .put("issuer", "Org1")
            .put("issuerMspId", "Org1MSP");
    own.putObject("metadata")
        .put(
            "ownerSubjectHash",
            DocumentAccess.ownerHash("https://identity.example", "opaque-citizen-or-staff-id"));
    when(ledger.get("DOC-001")).thenReturn(own);
    var other = own.deepCopy();
    other
        .withObject("metadata")
        .put(
            "ownerSubjectHash",
            DocumentAccess.ownerHash("https://identity.example", "someone-else"));
    when(ledger.get("DOC-002")).thenReturn(other);
    String auth = "Bearer " + valid(List.of("CITIZEN"));
    mvc.perform(get("/api/documents/DOC-001").with(https()).header("Authorization", auth))
        .andExpect(status().isOk());
    mvc.perform(get("/api/documents/DOC-002").with(https()).header("Authorization", auth))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/documents/DOC-002/file").with(https()).header("Authorization", auth))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/documents/DOC-002/verify")
                .with(https())
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"hash\":\"sha256:" + "a".repeat(64) + "\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/documents/DOC-001/history").with(https()).header("Authorization", auth))
        .andExpect(status().isForbidden());
    verifyNoInteractions(ipfs);
  }

  @Test
  void searchDerivesCitizenOwnerAndRejectsForeignResults() throws Exception {
    var result = new ObjectMapper().createObjectNode(); result.putArray("items"); result.put("nextAfter", "");
    when(ledger.search(eq(""), eq("ALL"), startsWith("sha256:"), eq(""))).thenReturn(result);
    mvc.perform(get("/api/documents").with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isOk());
    verify(ledger).search(eq(""), eq("ALL"), startsWith("sha256:"), eq(""));
    var page = new ObjectMapper().createObjectNode(); page.putArray("items"); page.put("nextAfter", "PRIVATE-OTHER-ID");
    when(ledger.search("", "ALL", "", "")).thenReturn(page);
    String json = mvc.perform(get("/api/documents").with(https()).header("Authorization", "Bearer " + valid(List.of("READER"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String readerCursor = new ObjectMapper().readTree(json).path("nextCursor").asText();
    mvc.perform(get("/api/documents").param("cursor", readerCursor).with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isBadRequest());
    result.withArray("items").addObject().put("issuer", "Other").put("issuerMspId", "OtherMSP");
    mvc.perform(get("/api/documents").with(https()).header("Authorization", "Bearer " + valid(List.of("CITIZEN"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/documents").with(https()).header("Authorization", "Bearer " + valid(List.of("ISSUER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void departmentReaderCannotReadAnotherDepartmentsRecord() throws Exception {
    var other =
        new ObjectMapper().createObjectNode().put("issuer", "Org2").put("issuerMspId", "Org2MSP");
    when(ledger.get("DOC-001")).thenReturn(other);
    mvc.perform(
            get("/api/documents/DOC-001")
                .with(https())
                .header("Authorization", "Bearer " + valid(List.of("READER"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void restApiDoesNotExposeViewOrSseRendering() throws Exception {
    String auth = "Bearer " + valid(List.of("READER"));
    mvc.perform(get("/unmapped-view").with(https()).header("Authorization", auth))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/documents/DOC-001")
                .with(https())
                .header("Authorization", auth)
                .accept("text/event-stream"))
        .andExpect(status().isNotAcceptable());
  }
}
