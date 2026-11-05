-- US-2.1 (schema-ahead, R1 plan §7). README §10. SQL Server syntax; runs unchanged on H2 (ADR-003 rule 1).
CREATE TABLE endpoint (
  id                 UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
  name               VARCHAR(63)      NOT NULL,
  description        NVARCHAR(512)    NULL,
  base_url           VARCHAR(2048)    NOT NULL,
  health_path        VARCHAR(512)     NULL,
  connect_timeout_ms INT              NOT NULL,
  read_timeout_ms    INT              NOT NULL,
  http_version       VARCHAR(16)      NOT NULL,
  auth_type          VARCHAR(32)      NOT NULL,
  auth_config        NVARCHAR(MAX)    NULL,
  resilience_config  NVARCHAR(MAX)    NULL,
  enabled            BIT              NOT NULL DEFAULT 1,
  row_version        BIGINT           NOT NULL DEFAULT 0,
  created_at         DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  created_by         NVARCHAR(256)    NOT NULL,
  updated_at         DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  updated_by         NVARCHAR(256)    NOT NULL,
  CONSTRAINT uq_endpoint_name UNIQUE (name)
);
