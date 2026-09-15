package dev.pratik.poi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Private UAT workspace. It never writes synthetic cases or payment decisions. */
@RestController
@RequestMapping("/api/uat/snapshots")
public class UatController {
  static final int MAX_REQUEST_BYTES = 16384;
  private final UatService service;

  public UatController(UatService service) { this.service = service; }

  @GetMapping
  public ObjectNode list(Authentication auth) { return service.list(Actor.from(auth)); }

  @GetMapping("/{id}")
  public ObjectNode snapshot(@PathVariable String id, Authentication auth) {
    return service.snapshot(id, Actor.from(auth));
  }

  @GetMapping("/{id}/questions")
  public ObjectNode history(@PathVariable String id, Authentication auth) {
    return service.history(id, Actor.from(auth));
  }

  @PostMapping(value = "/{id}/questions", consumes = "application/json")
  public ObjectNode question(@PathVariable String id, HttpServletRequest request, Authentication auth)
      throws IOException {
    Actor actor = Actor.from(auth);
    actor.requireWriter();
    byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
    if (body.length > MAX_REQUEST_BYTES)
      throw new ApiException(413, "UAT_REQUEST_TOO_LARGE", "Question requests are limited to 16 KiB.");
    return service.answer(id, actor, body);
  }
}
