-- ADR-003 F16: SQL Server allows only one NULL in a unique index, so the idempotency key is unique only where set.
CREATE UNIQUE INDEX uq_pipeline_run_idempotency
  ON pipeline_run (pipeline_code, created_by, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
