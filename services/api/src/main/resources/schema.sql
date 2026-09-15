CREATE TABLE IF NOT EXISTS payment_case (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL,
  status VARCHAR(40) NOT NULL, version BIGINT NOT NULL,
  updated_at VARCHAR(40) NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS case_tenant_idx ON payment_case(tenant_id);
CREATE TABLE IF NOT EXISTS investigation (
  id VARCHAR(100) PRIMARY KEY, case_id VARCHAR(100) NOT NULL REFERENCES payment_case(id),
  tenant_id VARCHAR(100) NOT NULL, created_by VARCHAR(100) NOT NULL,
  created_at VARCHAR(40) NOT NULL, case_version BIGINT NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS investigation_case_idx ON investigation(tenant_id,case_id);
CREATE TABLE IF NOT EXISTS review_decision (
  id VARCHAR(100) PRIMARY KEY, case_id VARCHAR(100) NOT NULL REFERENCES payment_case(id),
  investigation_id VARCHAR(100) NOT NULL UNIQUE REFERENCES investigation(id),
  tenant_id VARCHAR(100) NOT NULL, actor VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL, body TEXT NOT NULL,
  UNIQUE(tenant_id,actor,idempotency_key)
);
CREATE TABLE IF NOT EXISTS audit_event (
  id VARCHAR(100) PRIMARY KEY, case_id VARCHAR(100) NOT NULL REFERENCES payment_case(id),
  tenant_id VARCHAR(100) NOT NULL, occurred_at VARCHAR(40) NOT NULL,
  actor VARCHAR(100) NOT NULL, action VARCHAR(100) NOT NULL, detail TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS audit_case_idx ON audit_event(tenant_id,case_id,occurred_at);
CREATE TABLE IF NOT EXISTS obpm_evidence_snapshot (
  case_id VARCHAR(100) NOT NULL REFERENCES payment_case(id), tenant_id VARCHAR(100) NOT NULL,
  evidence_version BIGINT NOT NULL, evidence_hash VARCHAR(64) NOT NULL,
  source_snapshot_id VARCHAR(100) NOT NULL, extracted_at VARCHAR(40) NOT NULL,
  imported_at VARCHAR(40) NOT NULL, body TEXT NOT NULL,
  PRIMARY KEY(case_id,evidence_version), UNIQUE(case_id,source_snapshot_id)
);
CREATE TABLE IF NOT EXISTS obpm_import (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL,
  case_id VARCHAR(100) NOT NULL REFERENCES payment_case(id),
  imported_at VARCHAR(40) NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS obpm_import_tenant_idx ON obpm_import(tenant_id,imported_at);
CREATE TABLE IF NOT EXISTS fcr_discovery_candidate (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, identity_hash VARCHAR(64) NOT NULL,
  org_branch VARCHAR(10) NOT NULL, org_bank VARCHAR(10) NOT NULL,
  payment_reference VARCHAR(200) NOT NULL, utr VARCHAR(200), created_at VARCHAR(40) NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS fcr_candidate_lookup_idx ON fcr_discovery_candidate(tenant_id,org_branch,org_bank,payment_reference);
CREATE INDEX IF NOT EXISTS fcr_candidate_utr_idx ON fcr_discovery_candidate(tenant_id,org_branch,org_bank,utr);
CREATE TABLE IF NOT EXISTS fcr_discovery_batch (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, observed_at VARCHAR(100) NOT NULL, body TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS fcr_payment_case (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, identity_hash VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL, body TEXT NOT NULL, UNIQUE(tenant_id,identity_hash)
);
CREATE TABLE IF NOT EXISTS fcr_case_number_mutex (
  id INTEGER PRIMARY KEY CHECK (id=1)
);
INSERT INTO fcr_case_number_mutex(id) SELECT 1
  WHERE NOT EXISTS (SELECT 1 FROM fcr_case_number_mutex WHERE id=1);
CREATE TABLE IF NOT EXISTS fcr_case_number_counter (
  number_date VARCHAR(8) PRIMARY KEY, last_value BIGINT NOT NULL CHECK (last_value BETWEEN 0 AND 99999)
);
CREATE TABLE IF NOT EXISTS fcr_case_number (
  case_id VARCHAR(100) PRIMARY KEY REFERENCES fcr_payment_case(id), tenant_id VARCHAR(100) NOT NULL,
  case_number VARCHAR(13) NOT NULL UNIQUE, number_date VARCHAR(8) NOT NULL,
  sequence_no BIGINT NOT NULL CHECK (sequence_no BETWEEN 1 AND 99999),
  UNIQUE(number_date,sequence_no)
);
CREATE INDEX IF NOT EXISTS fcr_case_number_tenant_idx ON fcr_case_number(tenant_id,case_number);
CREATE TABLE IF NOT EXISTS fcr_case_command (
  tenant_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
  request_hash VARCHAR(64) NOT NULL, body TEXT NOT NULL, PRIMARY KEY(tenant_id,actor_id,idempotency_key)
);
CREATE TABLE IF NOT EXISTS fcr_case_evidence (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  version INTEGER NOT NULL, created_at VARCHAR(40) NOT NULL, summary TEXT NOT NULL, body TEXT NOT NULL,
  UNIQUE(tenant_id,case_id,version)
);
CREATE INDEX IF NOT EXISTS fcr_case_evidence_case_idx ON fcr_case_evidence(tenant_id,case_id,version);
CREATE TABLE IF NOT EXISTS fcr_case_evidence_command (
  tenant_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL, snapshot_id VARCHAR(100) NOT NULL,
  PRIMARY KEY(tenant_id,actor_id,case_id,idempotency_key)
);
CREATE TABLE IF NOT EXISTS fcr_case_investigation (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  evidence_id VARCHAR(100) NOT NULL, created_at VARCHAR(40) NOT NULL, actor_id VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL, summary TEXT NOT NULL, body TEXT NOT NULL,
  UNIQUE(tenant_id,actor_id,case_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS fcr_case_investigation_case_idx ON fcr_case_investigation(tenant_id,case_id,created_at);
CREATE TABLE IF NOT EXISTS fcr_case_report (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  created_at VARCHAR(40) NOT NULL, actor_id VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  report_hash VARCHAR(64) NOT NULL, body TEXT NOT NULL,
  UNIQUE(tenant_id,actor_id,case_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS fcr_case_report_case_idx ON fcr_case_report(tenant_id,case_id,created_at);
CREATE TABLE IF NOT EXISTS fcr_case_management (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  version BIGINT NOT NULL, owner_id VARCHAR(100), priority VARCHAR(20) NOT NULL,
  updated_at VARCHAR(40) NOT NULL, PRIMARY KEY(tenant_id,case_id)
);
CREATE TABLE IF NOT EXISTS fcr_case_management_event (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  version BIGINT NOT NULL, action VARCHAR(60) NOT NULL, occurred_at VARCHAR(40) NOT NULL,
  actor_id VARCHAR(100) NOT NULL, body TEXT NOT NULL, UNIQUE(tenant_id,case_id,version)
);
CREATE INDEX IF NOT EXISTS fcr_management_event_case_idx ON fcr_case_management_event(tenant_id,case_id,version);
CREATE TABLE IF NOT EXISTS fcr_case_management_command (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  PRIMARY KEY(tenant_id,case_id,actor_id,idempotency_key)
);
CREATE TABLE IF NOT EXISTS fcr_case_lifecycle (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL REFERENCES fcr_payment_case(id),
  state VARCHAR(20) NOT NULL CHECK (state IN ('ACTIVE','ARCHIVED','DELETED')),
  version BIGINT NOT NULL, updated_at VARCHAR(40) NOT NULL, PRIMARY KEY(tenant_id,case_id)
);
CREATE TABLE IF NOT EXISTS fcr_case_lifecycle_event (
  id VARCHAR(100) PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL,
  version BIGINT NOT NULL, occurred_at VARCHAR(40) NOT NULL, body TEXT NOT NULL,
  UNIQUE(tenant_id,case_id,version)
);
CREATE TABLE IF NOT EXISTS fcr_case_lifecycle_command (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL, body TEXT NOT NULL,
  PRIMARY KEY(tenant_id,case_id,actor_id,idempotency_key)
);
