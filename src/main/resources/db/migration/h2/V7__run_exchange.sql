-- US-7.7 (schema-ahead). H2 counterpart of sqlserver/V7: plain table, same columns and keys (ADR-003 F8).
CREATE TABLE run_exchange (
  id             UNIQUEIDENTIFIER NOT NULL,
  run_id         UNIQUEIDENTIFIER NOT NULL,
  kind           VARCHAR(16)      NOT NULL,
  event_seq      BIGINT           NULL,
  http_status    INT              NULL,
  headers        NVARCHAR(MAX)    NULL,
  body           NVARCHAR(MAX)    NULL,
  bytes          BIGINT           NULL,
  truncated      BIT              NOT NULL DEFAULT 0,
  final          BIT              NOT NULL DEFAULT 0,
  occurred_at    DATETIME2(6)     NOT NULL,
  payload_purged BIT              NOT NULL DEFAULT 0,
  CONSTRAINT pk_run_exchange PRIMARY KEY (id, occurred_at),
  CONSTRAINT ck_run_exchange_kind CHECK (kind IN ('INBOUND_REQUEST', 'CALLER_RESPONSE'))
);
CREATE INDEX ix_run_exchange_run_seq ON run_exchange (run_id, event_seq);
