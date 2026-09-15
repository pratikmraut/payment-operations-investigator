#!/usr/bin/env bash
set -euo pipefail

# Only this project database and the explicitly named worker role/schema are changed.
# Values enter SQL through psql literal quoting and format(%I/%L), never shell SQL interpolation.
: "${POSTGRES_USER:=poi}"
: "${POSTGRES_DB:=poi}"
: "${POI_VECTOR_DB_PASSWORD:=poi-vector-local-only}"

psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=vector_password="$POI_VECTOR_DB_PASSWORD" \
  --set=project_database="$POSTGRES_DB" <<'SQL'
BEGIN;
SELECT 'CREATE ROLE poi_vectors LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'poi_vectors')
\gexec
ALTER ROLE poi_vectors LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD :'vector_password';
CREATE SCHEMA IF NOT EXISTS poi_knowledge AUTHORIZATION poi_vectors;
ALTER SCHEMA poi_knowledge OWNER TO poi_vectors;
REVOKE ALL ON SCHEMA poi_knowledge FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM poi_vectors;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM poi_vectors;
SELECT format('GRANT CONNECT ON DATABASE %I TO poi_vectors', :'project_database')
\gexec
SELECT format('ALTER ROLE poi_vectors IN DATABASE %I SET search_path = poi_knowledge, public', :'project_database')
\gexec
COMMIT;
SQL
