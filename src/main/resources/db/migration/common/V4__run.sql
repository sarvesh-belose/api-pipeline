-- US-4.1 (schema-ahead, R1 plan §7). README §10: every run and step column up front, including those used later
-- by US-5.x and US-6.x. The nullable unique idempotency index is a vendor pair in V4_1 (ADR-003 F16).
CREATE TABLE pipeline_run (
  id                 UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
  pipeline_code      VARCHAR(64)      NOT NULL,
  pipeline_version   INT              NOT NULL,
  status             VARCHAR(32)      NOT NULL,
  mode               VARCHAR(8)       NOT NULL,
  correlation_id     VARCHAR(128)     NOT NULL,
  idempotency_key    VARCHAR(255)     NULL,
  request_hash       CHAR(64)         NULL,
  caller_webhook_url VARCHAR(2048)    NULL,
  created_by         NVARCHAR(256)    NOT NULL,
  created_at         DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  started_at         DATETIME2(6)     NULL,
  segment_started_at DATETIME2(6)     NULL,
  deadline_at        DATETIME2(6)     NULL,
  completed_at       DATETIME2(6)     NULL,
  ended_at           DATETIME2(6)     NULL,
  error_code         VARCHAR(64)      NULL,
  error_message      NVARCHAR(2048)   NULL,
  error_step_id      VARCHAR(64)      NULL,
  stop_reason        VARCHAR(16)      NULL,
  compensate_on_stop BIT              NOT NULL DEFAULT 0,
  event_seq          BIGINT           NOT NULL DEFAULT 0,
  resume_count       INT              NOT NULL DEFAULT 0,
  owner_instance     VARCHAR(128)     NULL,
  lease_epoch        BIGINT           NOT NULL DEFAULT 0,
  lease_until        DATETIME2(6)     NULL,
  wakeup_requested   BIT              NOT NULL DEFAULT 0,
  context_purged_at  DATETIME2(6)     NULL,
  legal_hold         BIT              NOT NULL DEFAULT 0,
  CONSTRAINT ck_pipeline_run_mode CHECK (mode IN ('SYNC', 'ASYNC')),
  CONSTRAINT ck_pipeline_run_stop_reason CHECK (stop_reason IS NULL OR stop_reason IN ('FAIL_RUN', 'DEADLINE', 'CANCEL'))
);
CREATE INDEX ix_pipeline_run_lease ON pipeline_run (owner_instance, lease_until);
CREATE INDEX ix_pipeline_run_wakeup ON pipeline_run (wakeup_requested);
CREATE INDEX ix_pipeline_run_pipeline_created ON pipeline_run (pipeline_code, created_at);
CREATE INDEX ix_pipeline_run_correlation ON pipeline_run (correlation_id);
CREATE INDEX ix_pipeline_run_creator_created ON pipeline_run (created_by, created_at);
CREATE INDEX ix_pipeline_run_status_ended ON pipeline_run (status, ended_at);

CREATE TABLE step_execution (
  id                   UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
  run_id               UNIQUEIDENTIFIER NOT NULL,
  step_id              VARCHAR(64)      NOT NULL,
  exec_no              INT              NOT NULL DEFAULT 1,
  status               VARCHAR(32)      NOT NULL,
  skip_reason          VARCHAR(64)      NULL,
  mode                 VARCHAR(16)      NOT NULL,
  attempts             INT              NOT NULL DEFAULT 0,
  http_status          INT              NULL,
  fallback_applied     BIT              NOT NULL DEFAULT 0,
  started_at           DATETIME2(6)     NULL,
  ended_at             DATETIME2(6)     NULL,
  duration_ms          BIGINT           NULL,
  error_code           VARCHAR(64)      NULL,
  error_message        NVARCHAR(2048)   NULL,
  callback_deadline_at DATETIME2(6)     NULL,
  callback_kid         VARCHAR(64)      NULL,
  completion_seq       BIGINT           NULL,
  compensation_status  VARCHAR(32)      NULL,
  row_version          BIGINT           NOT NULL DEFAULT 0,
  CONSTRAINT fk_step_execution_run FOREIGN KEY (run_id) REFERENCES pipeline_run (id),
  CONSTRAINT uq_step_execution_run_step UNIQUE (run_id, step_id)
);
CREATE INDEX ix_step_execution_callback_deadline ON step_execution (status, callback_deadline_at);

-- content is encrypted by the application (US-7.6), so it is binary.
CREATE TABLE run_context (
  run_id     UNIQUEIDENTIFIER NOT NULL,
  entry_key  VARCHAR(64)      NOT NULL,
  content    VARBINARY(MAX)   NOT NULL,
  key_id     VARCHAR(64)      NULL,
  created_at DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  updated_at DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  CONSTRAINT pk_run_context PRIMARY KEY (run_id, entry_key),
  CONSTRAINT fk_run_context_run FOREIGN KEY (run_id) REFERENCES pipeline_run (id)
);
