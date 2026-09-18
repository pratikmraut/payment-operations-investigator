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
CREATE TABLE IF NOT EXISTS fcr_case_search (
  case_id VARCHAR(100) PRIMARY KEY REFERENCES fcr_payment_case(id) ON DELETE CASCADE,
  tenant_id VARCHAR(100) NOT NULL, org_bank VARCHAR(10) NOT NULL, org_branch VARCHAR(10) NOT NULL,
  payment_reference VARCHAR(200) NOT NULL, case_number VARCHAR(13),
  owner_id VARCHAR(100), owner_name VARCHAR(200), priority VARCHAR(20) NOT NULL, priority_rank INTEGER NOT NULL,
  workflow_status VARCHAR(30) NOT NULL, lifecycle_state VARCHAR(20) NOT NULL,
  lifecycle_version BIGINT NOT NULL, management_version BIGINT NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, created_nanos INTEGER NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, updated_nanos INTEGER NOT NULL,
  updated_at_text VARCHAR(40) NOT NULL, evidence_status VARCHAR(40) NOT NULL,
  latest_evidence_id VARCHAR(100), latest_evidence_version INTEGER NOT NULL, latest_source VARCHAR(30),
  coverage_state VARCHAR(30) NOT NULL, version_count INTEGER NOT NULL, row_count INTEGER NOT NULL,
  evidence_sort_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, evidence_sort_nanos INTEGER NOT NULL, metadata_error BOOLEAN NOT NULL,
  search_text TEXT NOT NULL, library_search_text TEXT NOT NULL, evidence_currency TEXT
);
ALTER TABLE fcr_case_search ADD COLUMN IF NOT EXISTS evidence_currency TEXT;
CREATE INDEX IF NOT EXISTS fcr_case_search_scope_idx ON fcr_case_search(tenant_id,org_bank,org_branch,lifecycle_state,created_at,created_nanos,case_id);
CREATE INDEX IF NOT EXISTS fcr_case_search_updated_idx ON fcr_case_search(tenant_id,lifecycle_state,updated_at,updated_nanos,case_id);
CREATE INDEX IF NOT EXISTS fcr_case_search_owner_idx ON fcr_case_search(tenant_id,lifecycle_state,owner_id,workflow_status,created_at,created_nanos,case_id);
CREATE INDEX IF NOT EXISTS fcr_case_search_number_idx ON fcr_case_search(tenant_id,case_number,case_id);
CREATE INDEX IF NOT EXISTS fcr_case_search_evidence_idx ON fcr_case_search(tenant_id,coverage_state,latest_source,evidence_sort_at,evidence_sort_nanos,case_id);
CREATE TABLE IF NOT EXISTS fcr_case_history_item (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL REFERENCES fcr_payment_case(id) ON DELETE CASCADE,
  kind VARCHAR(20) NOT NULL, item_id VARCHAR(200) NOT NULL,
  sort_seconds BIGINT NOT NULL, sort_nanos INTEGER NOT NULL, body TEXT,
  PRIMARY KEY(tenant_id,case_id,kind,item_id)
);
CREATE INDEX IF NOT EXISTS fcr_case_history_page_idx ON fcr_case_history_item(tenant_id,case_id,kind,sort_seconds,sort_nanos,item_id);
CREATE INDEX IF NOT EXISTS fcr_case_investigation_filter_idx ON fcr_case_investigation(tenant_id,case_id,evidence_id,status,id);
CREATE TABLE IF NOT EXISTS fcr_case_job_queue (
  job_id VARCHAR(100) PRIMARY KEY REFERENCES fcr_case_investigation(id) ON DELETE CASCADE,
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL,
  requested_seconds BIGINT NOT NULL, requested_nanos INTEGER NOT NULL, eligible_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS fcr_case_job_dispatch_idx ON fcr_case_job_queue(eligible_at,requested_seconds,requested_nanos,job_id);
CREATE TABLE IF NOT EXISTS fcr_case_dispatcher (
  id INTEGER PRIMARY KEY, job_id VARCHAR(100), lease_owner VARCHAR(100), lease_until BIGINT NOT NULL,
  fence BIGINT NOT NULL, turn_number BIGINT NOT NULL
);
INSERT INTO fcr_case_dispatcher(id,lease_until,fence,turn_number) SELECT 1,0,0,0 WHERE NOT EXISTS (SELECT 1 FROM fcr_case_dispatcher WHERE id=1);
CREATE TABLE IF NOT EXISTS fcr_case_dispatch_fair (
  tenant_id VARCHAR(100) NOT NULL, actor_id VARCHAR(100) NOT NULL, last_turn BIGINT NOT NULL,
  PRIMARY KEY(tenant_id,actor_id)
);
CREATE TABLE IF NOT EXISTS fcr_case_worker_cleanup (
  tenant_id VARCHAR(100) NOT NULL, case_id VARCHAR(100) NOT NULL, requested_at VARCHAR(40) NOT NULL,
  attempts INTEGER NOT NULL, eligible_at BIGINT NOT NULL, PRIMARY KEY(tenant_id,case_id)
);
