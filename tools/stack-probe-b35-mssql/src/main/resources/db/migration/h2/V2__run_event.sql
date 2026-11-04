-- H2 fast tier: same columns and keys as SQL Server, without partitioning, JSON checks or grants.
CREATE TABLE run_event (
  run_id UNIQUEIDENTIFIER NOT NULL,
  seq BIGINT NOT NULL,
  occurred_at DATETIME2(6) NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  details NVARCHAR(MAX),
  CONSTRAINT pk_run_event PRIMARY KEY (run_id, seq, occurred_at)
);
