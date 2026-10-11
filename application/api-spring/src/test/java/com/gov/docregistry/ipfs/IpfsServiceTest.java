package com.gov.docregistry.ipfs;

import static org.junit.jupiter.api.Assertions.*;

import com.gov.docregistry.web.RegistryException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class IpfsServiceTest {
  HttpServer server;
  IpfsService service;
  String cid = "bafy" + "a".repeat(50);
  AtomicReference<String> body = new AtomicReference<>();
  int status = 200;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v0/add",
        exchange -> {
          assertEquals("POST", exchange.getRequestMethod());
          assertTrue(exchange.getRequestURI().getQuery().contains("pin=true"));
          exchange.getRequestBody().readAllBytes();
          byte[] reply = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
    service =
        new IpfsService(
            new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), 100);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void parsesJsonWithWhitespaceRatherThanSubstringOffsets() {
    body.set("{ \"Hash\" : \"" + cid + "\", \"Size\" : \"12\" }");
    assertEquals(cid, service.addBytes(new byte[5], "file"));
  }

  @Test
  void missingCidOrMalformedResponseIsSanitized() {
    for (String response : new String[] {"{}", "not JSON", "{\"Hash\":\"bad\"}"}) {
      body.set(response);
      RegistryException e =
          assertThrows(RegistryException.class, () -> service.addBytes(new byte[5], "file"));
      assertEquals("DEPENDENCY_UNAVAILABLE", e.code());
    }
  }

  @Test
  void nonSuccessResponseIsNotTreatedAsUpload() {
    body.set("internal diagnostic");
    status = 500;
    assertThrows(RegistryException.class, () -> service.addBytes(new byte[5], "file"));
  }

  @Test
  void downloadSizeBoundStopsOversizedResponse() {
    server.createContext(
        "/api/v0/cat",
        exchange -> {
          byte[] bytes = new byte[200];
          exchange.sendResponseHeaders(200, 200);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    assertThrows(RegistryException.class, () -> service.getBytes(cid));
  }
}
