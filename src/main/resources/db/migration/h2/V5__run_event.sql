-- US-7.1 (schema-ahead). H2 counterpart of sqlserver/V5: the same columns and key, with no partitioning, ISJSON
-- check or grants (ADR-003 F8).
CREATE TABLE run_event (
  run_id      UNIQUEIDENTIFIER NOT NULL,
  seq         BIGINT           NOT NULL,
  event_type  VARCHAR(48)      NOT NULL,
  step_id     VARCHAR(64)      NULL,
  exec_no     INT              NULL,
  attempt     INT              NULL,
  occurred_at DATETIME2(6)     NOT NULL,
  instance_id VARCHAR(128)     NULL,
  actor       NVARCHAR(256)    NULL,
  details     NVARCHAR(MAX)    NULL,
  CONSTRAINT pk_run_event PRIMARY KEY (run_id, seq, occurred_at)
);
