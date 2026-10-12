package com.gov.docregistry.fabric;

import com.gov.docregistry.web.RegistryException;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.io.Reader;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.hyperledger.fabric.client.*;
import org.hyperledger.fabric.client.identity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class FabricGatewayService implements DocumentLedger {
  private final ObjectMapper mapper;
  private final String peer, authority, msp, network, chaincode;
  private final Path certificate, key, tls;
  private Gateway gateway;
  private ManagedChannel channel;
  private Contract contract;

  public FabricGatewayService(
      ObjectMapper mapper,
      @Value("${registry.fabric.peer}") String peer,
      @Value("${registry.fabric.authority}") String authority,
      @Value("${registry.fabric.msp}") String msp,
      @Value("${registry.fabric.channel:govchannel}") String network,
      @Value("${registry.fabric.chaincode:docregistry}") String chaincode,
      @Value("${registry.fabric.certificate}") Path certificate,
      @Value("${registry.fabric.private-key}") Path key,
      @Value("${registry.fabric.tls-certificate}") Path tls) {
    this.mapper = mapper;
    this.peer = peer;
    this.authority = authority;
    this.msp = msp;
    this.network = network;
    this.chaincode = chaincode;
    this.certificate = certificate;
    this.key = key;
    this.tls = tls;
  }

  private synchronized Contract connect() {
    if (contract != null) return contract;
    try (Reader certReader = Files.newBufferedReader(certificate);
        Reader keyReader = Files.newBufferedReader(key)) {
      var identity = new X509Identity(msp, Identities.readX509Certificate(certReader));
      var signer = Signers.newPrivateKeySigner(Identities.readPrivateKey(keyReader));
      channel =
          NettyChannelBuilder.forTarget(peer)
              .sslContext(GrpcSslContexts.forClient().trustManager(tls.toFile()).build())
              .overrideAuthority(authority)
              .build();
      gateway =
          Gateway.newInstance()
              .identity(identity)
              .signer(signer)
              .connection(channel)
              .evaluateOptions(o -> o.withDeadlineAfter(5, TimeUnit.SECONDS))
              .endorseOptions(o -> o.withDeadlineAfter(15, TimeUnit.SECONDS))
              .submitOptions(o -> o.withDeadlineAfter(5, TimeUnit.SECONDS))
              .commitStatusOptions(o -> o.withDeadlineAfter(30, TimeUnit.SECONDS))
              .connect();
      contract = gateway.getNetwork(network).getContract(chaincode);
      return contract;
    } catch (Exception e) {
      disconnect();
      throw RegistryException.unavailable();
    }
  }

  @PreDestroy
  public synchronized void disconnect() {
    contract = null;
    if (gateway != null) {
      gateway.close();
      gateway = null;
    }
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
    }
  }

  private JsonNode call(boolean write, String method, String... args) {
    try {
      Contract c = connect();
      byte[] bytes =
          write ? c.submitTransaction(method, args) : c.evaluateTransaction(method, args);
      return mapper.readTree(bytes);
    } catch (RegistryException e) {
      throw e;
    } catch (Exception e) {
      throw translate(e);
    }
  }

  static RegistryException translate(Exception e) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage());
      if (cause instanceof GatewayException gatewayError)
        gatewayError.getDetails().forEach(d -> messages.append(d.getMessage()));
    }
    String text = messages.toString();
    if (text.contains("REGISTRY_FORBIDDEN:")) return RegistryException.forbidden();
    if (text.contains("REGISTRY_DUPLICATE:"))
      return new RegistryException("DUPLICATE", HttpStatus.CONFLICT, "Document already exists");
    if (text.contains("REGISTRY_NOT_FOUND:"))
      return new RegistryException("NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found");
    if (text.contains("REGISTRY_VALIDATION:"))
      return RegistryException.validation("The ledger rejected invalid document data");
    return RegistryException.unavailable();
  }

  public JsonNode register(String id, String hash, String issuer, String subject, String metadata) {
    return call(true, "registerDocument", id, hash, issuer, subject, metadata);
  }

  public JsonNode get(String id) {
    return call(false, "getDocument", id);
  }

  public JsonNode verify(String id, String hash) {
    return call(false, "verifyDocument", id, hash);
  }

  public JsonNode revoke(String id, String reason) {
    return call(true, "revokeDocument", id, reason);
  }

  public JsonNode history(String id) {
    return call(false, "getDocumentHistory", id);
  }

  public JsonNode search(String prefix, String status, String owner, String after) {
    return call(false, "searchDocuments", prefix, status, owner, after);
  }

  public void ping() {
    call(false, "ping");
  }
}
