package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ObpmImportService {
  private final JdbcTemplate db;
  private final CaseStore cases;
  private final JsonSupport json;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final Path sampleDirectory;

  public ObpmImportService(JdbcTemplate db, CaseStore cases, JsonSupport json, ObjectMapper mapper,
      TransactionTemplate transactions, @Value("${poi.obpm-samples:../../data/obpm/samples}") String sampleDirectory) {
    this.db = db; this.cases = cases; this.json = json; this.mapper = mapper;
    this.transactions = transactions; this.sampleDirectory = Path.of(sampleDirectory).toAbsolutePath().normalize();
  }

  public ObjectNode ingest(JsonNode input, Actor actor) {
    actor.requireWriter();
    ObjectNode evidence = ObpmEvidenceValidator.validate(input);
    String hash = InvestigationService.hash(canonical(evidence).toString());
    JsonNode source = evidence.path("source"), payment = evidence.path("payment");
    String identity = mapper.createArrayNode().add(actor.tenantId()).add(source.path("deploymentId").asText())
        .add(source.path("hostCode").asText()).add(source.path("branchCode").asText())
        .add(payment.path("rail").asText()).add(payment.path("direction").asText())
        .add(payment.path("sourcePaymentId").asText()).toString();
    String caseId = "OBPM-" + InvestigationService.hash(identity).substring(0, 32);
    return transactions.execute(tx -> {
      boolean exists = db.queryForObject("SELECT COUNT(*) FROM payment_case WHERE id=? AND tenant_id=?",
          Integer.class, caseId, actor.tenantId()) > 0;
      ObjectNode current = exists ? cases.lockCase(caseId, actor.tenantId()) : null;
      long evidenceVersion = exists ? current.path("evidenceVersion").longValue() : 0;
      long caseVersion = exists ? current.path("version").longValue() : 0;
      String status = exists ? "UPDATED" : "CREATED";
      if (exists && hash.equals(current.path("evidenceHash").asText())) status = "UNCHANGED";
      else {
        if (exists && !Instant.parse(evidence.path("extractedAt").asText())
            .isAfter(Instant.parse(current.path("obpm").path("extractedAt").asText())))
          throw new ApiException(409, "STALE_OBPM_SNAPSHOT", "Changed evidence must have a later extraction cutoff. Refresh the source snapshot.");
        // A source snapshot ID names immutable content within one payment.
        if (db.queryForObject("SELECT COUNT(*) FROM obpm_evidence_snapshot WHERE case_id=? AND source_snapshot_id=?",
            Integer.class, caseId, evidence.path("snapshotId").asText()) > 0)
          throw new ApiException(409, "SNAPSHOT_ID_CONFLICT", "This snapshot ID already names different evidence. Use a new snapshot ID.");
        evidenceVersion++; caseVersion++;
        String now = Instant.now().toString();
        ObjectNode record = json.object().put("id", caseId).put("tenantId", actor.tenantId())
            .put("paymentId", payment.path("sourcePaymentId").asText()).put("domain", "OBPM_NEFT").put("rail", "NEFT")
            .put("title", "NEFT evidence · " + payment.path("sourcePaymentId").asText())
            .put("description", "Original synthetic outbound NEFT evidence; investigate its recorded queue state.")
            .put("priority", "HIGH").put("status", "OPEN").put("merchant", "Synthetic OBPM deployment")
            .put("amountMinor", payment.path("amountMinor").longValue()).put("currency", "INR")
            .put("createdAt", payment.path("createdAt").asText()).put("updatedAt", now)
            .put("policyDate", payment.path("activationDate").asText()).put("version", caseVersion)
            .put("evidenceVersion", evidenceVersion).put("evidenceHash", hash);
        record.set("obpm", evidence);
        record.putArray("events"); record.putArray("ledgerEntries"); record.putArray("webhooks"); record.putNull("provider");
        record.putArray("tags").add("synthetic").add("obpm").add("neft");
        MoneyFacts.calculate(record);
        if (exists) {
          int changed = db.update("UPDATE payment_case SET status='OPEN',version=?,updated_at=?,body=? WHERE id=? AND tenant_id=? AND version=?",
              caseVersion, now, record.toString(), caseId, actor.tenantId(), caseVersion - 1);
          if (changed != 1) throw new ApiException(409, "VERSION_CONFLICT", "Case changed during import; retry the snapshot.");
        } else db.update("INSERT INTO payment_case(id,tenant_id,status,version,updated_at,body) VALUES(?,?,'OPEN',?,?,?)",
            caseId, actor.tenantId(), caseVersion, now, record.toString());
        db.update("INSERT INTO obpm_evidence_snapshot(case_id,tenant_id,evidence_version,evidence_hash,source_snapshot_id,extracted_at,imported_at,body) VALUES(?,?,?,?,?,?,?,?)",
            caseId, actor.tenantId(), evidenceVersion, hash, evidence.path("snapshotId").asText(), evidence.path("extractedAt").asText(), now, evidence.toString());
      }
      ObjectNode receipt = json.object().put("importId", "IMP-" + UUID.randomUUID()).put("caseId", caseId)
          .put("status", status).put("evidenceVersion", evidenceVersion).put("evidenceHash", hash)
          .put("caseVersion", caseVersion).put("importedAt", Instant.now().toString());
      db.update("INSERT INTO obpm_import(id,tenant_id,case_id,imported_at,body) VALUES(?,?,?,?,?)",
          receipt.path("importId").asText(), actor.tenantId(), caseId, receipt.path("importedAt").asText(), receipt.toString());
      cases.audit(caseId, actor.tenantId(), actor.id(), "OBPM_IMPORT_" + status,
          receipt.path("importId").asText() + "; synthetic evidence v" + evidenceVersion + "; " + hash);
      return receipt;
    });
  }

  public List<ObjectNode> receipts(String tenant) {
    return db.query("SELECT body FROM obpm_import WHERE tenant_id=? ORDER BY imported_at DESC,id DESC LIMIT 50",
        (rs, n) -> json.object(rs.getString(1)), tenant);
  }
  public List<ObjectNode> versions(String id, String tenant, boolean includeEvidence) {
    cases.caseDetail(id, tenant);
    return db.query("SELECT * FROM obpm_evidence_snapshot WHERE case_id=? AND tenant_id=? ORDER BY evidence_version DESC",
        (rs, n) -> {
          ObjectNode version = json.object().put("evidenceVersion", rs.getLong("evidence_version"))
              .put("evidenceHash", rs.getString("evidence_hash")).put("sourceSnapshotId", rs.getString("source_snapshot_id"))
              .put("extractedAt", rs.getString("extracted_at")).put("importedAt", rs.getString("imported_at"));
          if (includeEvidence) version.set("evidence", json.object(rs.getString("body")));
          return version;
        }, id, tenant);
  }
  public List<ObjectNode> samples() {
    if (!Files.isDirectory(sampleDirectory)) return List.of();
    try (var files = Files.list(sampleDirectory)) {
      List<ObjectNode> result = new ArrayList<>();
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().limit(20).toList()) {
        if (!Files.isRegularFile(file) || Files.size(file) > ObpmController.MAX_BYTES) continue;
        ObjectNode payload = ObpmEvidenceValidator.validate(mapper.readTree(file.toFile()));
        String id = file.getFileName().toString().replaceFirst("\\.json$", "");
        ObjectNode item = json.object().put("id", id).put("title", id.replace('-', ' '))
            .put("description", "Original synthetic snapshot " + payload.path("snapshotId").asText());
        item.set("payload", payload); result.add(item);
      }
      return result;
    } catch (Exception ex) { throw new ApiException(503, "SAMPLE_CATALOG_UNAVAILABLE", "The local synthetic sample catalog could not be validated."); }
  }
  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = json.object(); List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names); names.forEach(name -> sorted.set(name, canonical(node.get(name)))); return sorted;
    }
    if (node.isArray()) { var array = mapper.createArrayNode(); node.forEach(value -> array.add(canonical(value))); return array; }
    return node;
  }
}
