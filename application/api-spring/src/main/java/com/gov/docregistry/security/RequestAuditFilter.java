package com.gov.docregistry.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded local rate windows; ingress phase also audits rejected authentication. */
public class RequestAuditFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger("registry.audit");
  private final int perMinute;
  private final boolean ingress;
  private final Map<String, Window> windows = new HashMap<>();

  private record Window(long minute, int count) {}

  public RequestAuditFilter(int perMinute) {
    this(perMinute, false);
  }

  public RequestAuditFilter(int perMinute, boolean ingress) {
    if (perMinute < 1) throw new IllegalArgumentException("Rate limit must be positive");
    this.perMinute = perMinute;
    this.ingress = ingress;
  }

  @Override
  protected String getAlreadyFilteredAttributeName() {
    return getClass().getName() + "." + ingress + ".FILTERED";
  }

  private synchronized boolean allow(String key) {
    long minute = System.currentTimeMillis() / 60000;
    windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
    Window old = windows.getOrDefault(key, new Window(minute, 0));
    if (old.count() >= perMinute || (!windows.containsKey(key) && windows.size() >= 10000))
      return false;
    windows.put(key, new Window(minute, old.count() + 1));
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    String requestId = ingress ? UUID.randomUUID().toString() : null;
    if (ingress) {
      response.setHeader("X-Request-ID", requestId);
      MDC.put("requestId", requestId);
    }
    try {
      String key = ingress ? hash(request.getRemoteAddr()) : actor();
      if (!allow(key)) {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response
            .getWriter()
            .write("{\"code\":\"RATE_LIMITED\",\"message\":\"Request limit exceeded\"}");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      if (ingress) {
        log.info(
            "request={} actor={} method={} route={} status={}",
            requestId,
            actor(),
            request.getMethod().replaceAll("[^A-Z]", ""),
            route(request.getRequestURI()),
            response.getStatus());
        MDC.remove("requestId");
      }
    }
  }

  static String route(String uri) {
    if (uri.equals("/api/documents") || uri.equals("/api/documents/upload")) return uri;
    if (uri.matches("/api/documents/[^/]+")) return "/api/documents/{id}";
    for (String suffix :
        List.of("verify", "verify-file", "revoke", "file", "history", "registration"))
      if (uri.matches("/api/documents/[^/]+/" + suffix)) return "/api/documents/{id}/" + suffix;
    return "/api/{unmatched}";
  }

  private static String actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? "anonymous" : hash(auth.getName());
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
