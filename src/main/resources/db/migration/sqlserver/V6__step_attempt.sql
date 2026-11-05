-- US-7.2 (schema-ahead). One row per HTTP attempt, inserted once and complete when the attempt ends, so it is
-- routed to its partition on insert and never updated by the application (README §10, ADR-003 F9).
CREATE TABLE step_attempt (
  id                  UNIQUEIDENTIFIER NOT NULL,
  run_id              UNIQUEIDENTIFIER NOT NULL,
  step_id             VARCHAR(64)      NOT NULL,
  exec_no             INT              NOT NULL,
  attempt_no          INT              NOT NULL,
  kind                VARCHAR(16)      NOT NULL,
  event_seq           BIGINT           NULL,
  endpoint_name       VARCHAR(63)      NOT NULL,
  method              VARCHAR(8)       NOT NULL,
  url                 VARCHAR(2048)    NOT NULL,
  request_headers     NVARCHAR(MAX)    NULL,
  request_body        NVARCHAR(MAX)    NULL,
  request_bytes       BIGINT           NULL,
  request_truncated   BIT              NOT NULL DEFAULT 0,
  response_status     INT              NULL,
  response_headers    NVARCHAR(MAX)    NULL,
  response_body       NVARCHAR(MAX)    NULL,
  response_bytes      BIGINT           NULL,
  response_truncated  BIT              NOT NULL DEFAULT 0,
  outcome             VARCHAR(32)      NOT NULL,
  error_class         VARCHAR(128)     NULL,
  error_message       NVARCHAR(2048)   NULL,
  circuit_state       VARCHAR(16)      NULL,
  next_retry_delay_ms BIGINT           NULL,
  started_at          DATETIME2(6)     NOT NULL,
  ended_at            DATETIME2(6)     NULL,
  duration_ms         BIGINT           NULL,
  payload_purged      BIT              NOT NULL DEFAULT 0,
  CONSTRAINT pk_step_attempt PRIMARY KEY CLUSTERED (id, started_at) ON ps_month (started_at),
  CONSTRAINT ck_step_attempt_kind CHECK (kind IN ('REQUEST', 'COMPENSATION'))
);
GO
CREATE UNIQUE INDEX uq_step_attempt_natural ON step_attempt (run_id, step_id, kind, attempt_no, started_at)
  ON ps_month (started_at);
GO
DENY UPDATE, DELETE ON step_attempt TO apiorch_app_role;
GRANT SELECT, UPDATE, DELETE, ALTER ON step_attempt TO apiorch_maint_role;
