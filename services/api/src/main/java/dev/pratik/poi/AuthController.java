package dev.pratik.poi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager manager;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository csrf;

  public AuthController(
      AuthenticationManager manager,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf) {
    this.manager = manager;
    this.contexts = contexts;
    this.csrf = csrf;
  }

  record Login(
      @NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {}

  @PostMapping(value = "/login", consumes = "application/json")
  public Map<String, Object> login(
      @Valid @RequestBody Login login, HttpServletRequest request, HttpServletResponse response) {
    // Cross-origin JSON login is blocked by the absence of CORS. Reject browser cross-site requests
    // explicitly as well.
    String site = request.getHeader("Sec-Fetch-Site");
    if ("cross-site".equals(site))
      throw new ApiException(403, "FORBIDDEN", "Cross-site login is not permitted.");
    try {
      Authentication auth =
          manager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  login.username(), login.password()));
      request.getSession(true);
      request.changeSessionId();
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, request, response);
      CsrfToken token = csrf.generateToken(request);
      csrf.saveToken(token, request, response);
      return Map.of("user", Actor.from(auth), "csrfToken", token.getToken());
    } catch (AuthenticationException e) {
      throw new ApiException(401, "INVALID_CREDENTIALS", "Username or password is incorrect.");
    }
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication auth, HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return Map.of("user", Actor.from(auth), "csrfToken", token.getToken());
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    var cookie = new jakarta.servlet.http.Cookie("POI_SESSION", "");
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setMaxAge(0);
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);
    return Map.of("status", "SIGNED_OUT");
  }
}
