package dev.pratik.poi;

/** Resolve display numbers at the HTTP boundary; services and stored bindings use immutable IDs. */
final class CaseRouteIdentity {
  private CaseRouteIdentity() {}

  static String canonical(PaymentDiscoveryService cases, Actor actor, String idOrNumber) {
    return idOrNumber.matches("[0-9]{13}")
        ? cases.caseDetail(actor, idOrNumber).path("id").asText()
        : idOrNumber;
  }
}
