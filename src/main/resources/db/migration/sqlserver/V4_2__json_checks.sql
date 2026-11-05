-- ADR-003 F8: JSON columns carry an ISJSON check on SQL Server only; H2 has no ISJSON, so no counterpart.
-- The columns are stored and returned, never queried inside (ADR-003 F15).
ALTER TABLE endpoint ADD CONSTRAINT ck_endpoint_auth_config_json CHECK (auth_config IS NULL OR ISJSON(auth_config) = 1);
ALTER TABLE endpoint ADD CONSTRAINT ck_endpoint_resilience_config_json
  CHECK (resilience_config IS NULL OR ISJSON(resilience_config) = 1);
ALTER TABLE pipeline_definition ADD CONSTRAINT ck_pipeline_definition_content_json CHECK (ISJSON(content) = 1);
