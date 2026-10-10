package com.gov.docregistry.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitTest {
  @Test
  void unauthenticatedRequestsAreLimitedAndUnknownPathsAreRedacted() throws Exception {
    var filter = new RequestAuditFilter(1, true);
    var first = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/documents/DOC-001"), first, new MockFilterChain());
    assertNotNull(first.getHeader("X-Request-ID"));
    var second = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/documents/DOC-002"), second, new MockFilterChain());
    assertEquals(429, second.getStatus());
    assertEquals(
        "/api/{unmatched}",
        RequestAuditFilter.route("/api/documents/DOC-001/confidential-extra-text"));
  }

  @Test
  void limitsAnAccountWithoutLoggingCredentials() throws Exception {
    RequestAuditFilter filter = new RequestAuditFilter(1);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("test-account", "unused"));
    try {
      MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/documents/DOC-001");
      MockHttpServletResponse firstResponse = new MockHttpServletResponse();
      filter.doFilter(first, firstResponse, new MockFilterChain());
      assertEquals(200, firstResponse.getStatus());
      MockHttpServletResponse secondResponse = new MockHttpServletResponse();
      filter.doFilter(
          new MockHttpServletRequest("GET", "/api/documents/DOC-001"),
          secondResponse,
          new MockFilterChain());
      assertEquals(429, secondResponse.getStatus());
      assertEquals("60", secondResponse.getHeader("Retry-After"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
