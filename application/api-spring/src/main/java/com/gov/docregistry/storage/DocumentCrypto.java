package com.gov.docregistry.storage;

import com.gov.docregistry.web.RegistryException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentCrypto {
  private static final byte[] MAGIC = "GOVDOC01".getBytes(StandardCharsets.US_ASCII);
  private final Map<String, SecretKeySpec> keys = new HashMap<>();
  private final String activeKey;
  private final SecureRandom random = new SecureRandom();

  public DocumentCrypto(
      ObjectMapper mapper,
      @Value("${registry.encryption.keys-file:.runtime/secrets/encryption.json}") Path file)
      throws Exception {
    JsonNode config = mapper.readTree(Files.readAllBytes(file));
    activeKey = config.path("activeKeyId").asText();
    var entries = config.path("keys").properties().iterator();
    while (entries.hasNext()) {
      var e = entries.next();
      byte[] key = Base64.getDecoder().decode(e.getValue().asText());
      if (!e.getKey().matches("[A-Za-z0-9_-]{1,64}") || key.length != 32)
        throw new IllegalStateException("Invalid document key configuration");
      keys.put(e.getKey(), new SecretKeySpec(key, "AES"));
      Arrays.fill(key, (byte) 0);
    }
    if (!keys.containsKey(activeKey))
      throw new IllegalStateException("Active document key is unavailable");
  }

  public String activeKeyId() {
    return activeKey;
  }

  public byte[] encrypt(String docId, byte[] plaintext) {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKey), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(docId.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length)
          .put(MAGIC)
          .put(nonce)
          .put(encrypted)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Document encryption failed");
    }
  }

  public byte[] decrypt(String docId, String keyId, byte[] envelope) {
    try {
      if (envelope.length < 36
          || !Arrays.equals(MAGIC, Arrays.copyOf(envelope, 8))
          || !keys.containsKey(keyId)) throw new GeneralSecurityException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          keys.get(keyId),
          new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 8, 20)));
      cipher.updateAAD(docId.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(envelope, 20, envelope.length - 20);
    } catch (GeneralSecurityException e) {
      throw new RegistryException(
          "INTEGRITY_ERROR", HttpStatus.BAD_GATEWAY, "Stored document could not be authenticated");
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
