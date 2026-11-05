-- ADR-003 F16 (corrected 2026-11-05): H2 has no filtered index, and in MODE=MSSQLServer it treats NULLs in a unique
-- index as equal, like SQL Server. NULLS DISTINCT gives the same behaviour as the filtered index on SQL Server.
CREATE UNIQUE NULLS DISTINCT INDEX uq_pipeline_run_idempotency ON pipeline_run (pipeline_code, created_by, idempotency_key);
