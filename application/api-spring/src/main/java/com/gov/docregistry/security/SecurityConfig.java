package com.gov.docregistry.security;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  public Pbkdf2PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ObjectMapper mapper,
      org.springframework.beans.factory.ObjectProvider<JwtDecoder> decoders,
      @Value("${registry.auth.mode:basic}") String mode,
      @Value("${registry.auth.users-file:.runtime/secrets/users.json}") Path users,
      @Value("${registry.auth.issuer-uri:}") String issuer,
      @Value("${registry.auth.audience:govdoc-api}") String audience,
      @Value("${registry.issuer:Org1}") String department,
      @Value("${registry.auth.require-https:false}") boolean https,
      @Value("${registry.auth.cors-origins:}") String origins,
      @Value("${registry.rate-limit.per-minute:120}") int rateLimit,
      @Value("${registry.rate-limit.per-ip-minute:600}") int ingressLimit)
      throws Exception {
    String identityOrigin = "";
    if (!issuer.isBlank()) {
      var uri = java.net.URI.create(issuer);
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
        throw new IllegalStateException("Identity issuer must be an HTTPS URL");
      identityOrigin = " https://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
    final String connections = "'self'" + identityOrigin;
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src " + connections + "; "
                + "img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")))
        .headers(h -> h.addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter("Referrer-Policy", "no-referrer")))
        .cors(c -> c.configurationSource(cors(origins)))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/", "/index.html", "/registry.css", "/registry.js",
                        "/oidc.js", "/vendor/keycloak.js", "/vendor/qrcode.js", "/api/client-config",
                        "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("OPERATOR")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication"
                                      + " required\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"FORBIDDEN\",\"message\":\"Operation not"
                                      + " permitted\"}");
                        }));
    if (https) http.redirectToHttps(c -> {});
    if (mode.equals("basic")) {
      JsonNode entries = mapper.readTree(Files.readAllBytes(users));
      if (!entries.isArray() || entries.isEmpty())
        throw new IllegalStateException("API users file must contain accounts");
      List<UserDetails> accounts = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (JsonNode entry : entries) {
        String username = entry.path("username").asText();
        String hash = entry.path("passwordHash").asText();
        List<String> roles = new ArrayList<>();
        entry.path("roles").forEach(r -> roles.add(r.asText()));
        if (!username.matches("[A-Za-z0-9_-]{3,64}")
            || !names.add(username)
            || !hash.matches("[a-f0-9]{96}")
            || roles.isEmpty()
            || !Set.of("READER", "ISSUER", "REVOKER", "OPERATOR").containsAll(roles))
          throw new IllegalStateException("Invalid API account configuration");
        accounts.add(
            User.withUsername(username).password(hash).roles(roles.toArray(String[]::new)).build());
      }
      http.userDetailsService(new InMemoryUserDetailsManager(accounts))
          .httpBasic(c -> c.realmName("Government document registry"));
    } else if (mode.equals("jwt")) {
      JwtDecoder decoder = decoders.getObject();
      JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
      roles.setAuthoritiesClaimName("roles");
      roles.setAuthorityPrefix("ROLE_");
      JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(roles);
      http.oauth2ResourceServer(
          c -> c.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)));
    } else throw new IllegalStateException("Unsupported authentication mode");
    http.addFilterAfter(
        new RequestAuditFilter(ingressLimit, true),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    http.addFilterAfter(new RequestAuditFilter(rateLimit), AuthorizationFilter.class);
    return http.build();
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "registry.auth.mode",
      havingValue = "jwt")
  public JwtDecoder jwtDecoder(
      @Value("${registry.auth.issuer-uri:}") String issuer,
      @Value("${registry.auth.audience:govdoc-api}") String audience,
      @Value("${registry.issuer:Org1}") String department) {
    if (!issuer.startsWith("https://"))
      throw new IllegalStateException("JWT issuer must use HTTPS");
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    decoder.setJwtValidator(tokenValidator(issuer, audience, department));
    return decoder;
  }

  static OAuth2TokenValidator<Jwt> tokenValidator(
      String issuer, String audience, String department) {
    OAuth2TokenValidator<Jwt> claims =
        jwt ->
            jwt.getSubject() != null
                    && !jwt.getSubject().isBlank()
                    && jwt.getSubject().length() <= 256
                    && jwt.getAudience().contains(audience)
                    && department.equals(jwt.getClaimAsString("department"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer), claims);
  }

  private CorsConfigurationSource cors(String origins) {
    CorsConfiguration c = new CorsConfiguration();
    if (!origins.isBlank())
      c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
    c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    c.setExposedHeaders(List.of("X-Request-ID"));
    c.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", c);
    return source;
  }
}
