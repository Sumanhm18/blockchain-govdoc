package com.gov.docregistry.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FabricErrorsTest {
  @Test
  void fabricProtobufMessagesLoadWithManagedRuntime() {
    var proposal = org.hyperledger.fabric.protos.peer.Proposal.newBuilder().build();
    var request =
        org.hyperledger.fabric.protos.gateway.EvaluateRequest.newBuilder()
            .setChannelId("test")
            .build();
    assertEquals(0, proposal.toByteArray().length);
    assertFalse(request.toByteString().isEmpty());
  }

  @Test
  void onlyStructuredContractCodesAreMappedToClientErrors() {
    for (String code : new String[] {"DUPLICATE", "NOT_FOUND", "FORBIDDEN", "VALIDATION"})
      assertEquals(
          code,
          FabricGatewayService.translate(new Exception("REGISTRY_" + code + ": arbitrary details"))
              .code());
    assertEquals(
        "DEPENDENCY_UNAVAILABLE",
        FabricGatewayService.translate(new Exception("connection already exists but failed"))
            .code());
    assertEquals(
        "DEPENDENCY_UNAVAILABLE",
        FabricGatewayService.translate(new Exception("internal missing TLS path")).code());
  }
}
