package dev.pratik.poi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(
      @Value("${poi.demo-password}") String password,
      PasswordEncoder encoder,
      Environment environment) {
    if (Arrays.stream(environment.getActiveProfiles())
        .anyMatch(p -> p.equals("prod") || p.equals("production")))
      throw new IllegalStateException(
          "Demo authentication is disabled in production profiles. Configure a real identity provider before deployment.");
    if (password.length() < 12)
      throw new IllegalStateException("POI_DEMO_PASSWORD must contain at least 12 characters.");
    String encoded = encoder.encode(password);
    return new InMemoryUserDetailsManager(
        User.withUsername("analyst").password(encoded).roles("ANALYST").build(),
        User.withUsername("reviewer").password(encoded).roles("REVIEWER").build(),
        User.withUsername("viewer").password(encoded).roles("VIEWER").build(),
        User.withUsername("other").password(encoded).roles("ANALYST").build(),
        User.withUsername("admin").password(encoded).roles("ADMIN").build());
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  HttpSessionSecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
    repository.setHeaderName("X-CSRF-Token");
    return repository;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      HttpSessionSecurityContextRepository context,
      HttpSessionCsrfTokenRepository csrf,
      ObjectMapper mapper)
      throws Exception {
    return http.securityContext(c -> c.securityContextRepository(context).requireExplicitSave(true))
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/api/auth/login"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/health", "/api/auth/login", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            error(mapper, req, res, 401, "UNAUTHENTICATED", "Sign in to continue."))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            error(
                                mapper,
                                req,
                                res,
                                403,
                                "FORBIDDEN",
                                "Role or CSRF token does not permit this request.")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .build();
  }

  static void error(
      ObjectMapper mapper,
      HttpServletRequest req,
      HttpServletResponse res,
      int status,
      String code,
      String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    mapper.writeValue(
        res.getOutputStream(),
        Map.of(
            "code",
            code,
            "message",
            message,
            "requestId",
            String.valueOf(req.getAttribute("requestId"))));
  }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    req.setAttribute("requestId", id);
    res.setHeader("X-Request-Id", id);
    res.setHeader("Cache-Control", "no-store");
    chain.doFilter(req, res);
  }
}
