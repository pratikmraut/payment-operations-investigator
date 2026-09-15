package dev.pratik.poi;

import org.springframework.security.core.Authentication;

public record Actor(String id, String name, String role, String tenantId) {
  static java.util.List<Actor> knownActors() {
    return java.util.List.of(
        new Actor("analyst", "Aarav · Analyst", "ANALYST", "northstar"),
        new Actor("reviewer", "Maya · Reviewer", "REVIEWER", "northstar"),
        new Actor("viewer", "Sam · Viewer", "VIEWER", "northstar"),
        new Actor("other", "River · Analyst", "ANALYST", "silverline"),
        new Actor("admin", "Case administrator", "ADMIN", "northstar"));
  }
  public static Actor from(Authentication auth) {
    if (auth == null || !auth.isAuthenticated())
      throw new ApiException(401, "UNAUTHENTICATED", "Sign in to continue.");
    return knownActors().stream().filter(actor -> actor.id().equals(auth.getName())).findFirst()
        .orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Unknown demo identity."));
  }

  public void requireWriter() {
    if (!role.equals("ANALYST") && !role.equals("REVIEWER"))
      throw new ApiException(403, "FORBIDDEN", "An analyst or reviewer is required for this action.");
  }

  public void requireReviewer() {
    if (!role.equals("REVIEWER"))
      throw new ApiException(403, "FORBIDDEN", "A reviewer is required.");
  }
  public void requireAdministrator() {
    if (!role.equals("ADMIN")) throw new ApiException(403,"FORBIDDEN","A case administrator is required for permanent deletion.");
  }
  public void requireLifecycleManager() {
    if (!java.util.Set.of("ANALYST","REVIEWER","ADMIN").contains(role))
      throw new ApiException(403,"FORBIDDEN","An analyst, reviewer or case administrator is required.");
  }
}
