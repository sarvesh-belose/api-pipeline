-- US-3.1 (schema-ahead, R1 plan §7). README §10. Version 0 is the draft, so UNIQUE(code, version) allows exactly
-- one draft per pipeline.
CREATE TABLE pipeline (
  code            VARCHAR(64)   NOT NULL PRIMARY KEY,
  description     NVARCHAR(512) NULL,
  enabled         BIT           NOT NULL DEFAULT 1,
  disabled_reason NVARCHAR(512) NULL,
  row_version     BIGINT        NOT NULL DEFAULT 0,
  created_at      DATETIME2(6)  NOT NULL DEFAULT SYSUTCDATETIME(),
  created_by      NVARCHAR(256) NOT NULL,
  updated_at      DATETIME2(6)  NOT NULL DEFAULT SYSUTCDATETIME(),
  updated_by      NVARCHAR(256) NOT NULL
);

CREATE TABLE pipeline_definition (
  id              UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
  code            VARCHAR(64)      NOT NULL,
  version         INT              NOT NULL,
  status          VARCHAR(16)      NOT NULL,
  content         NVARCHAR(MAX)    NOT NULL,
  checksum        CHAR(64)         NOT NULL,
  comment         NVARCHAR(1024)   NULL,
  source_version  INT              NULL,
  source_checksum CHAR(64)         NULL,
  row_version     BIGINT           NOT NULL DEFAULT 0,
  created_at      DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  created_by      NVARCHAR(256)    NOT NULL,
  updated_at      DATETIME2(6)     NOT NULL DEFAULT SYSUTCDATETIME(),
  updated_by      NVARCHAR(256)    NOT NULL,
  published_at    DATETIME2(6)     NULL,
  published_by    NVARCHAR(256)    NULL,
  CONSTRAINT fk_pipeline_definition_pipeline FOREIGN KEY (code) REFERENCES pipeline (code),
  CONSTRAINT uq_pipeline_definition_code_version UNIQUE (code, version),
  CONSTRAINT ck_pipeline_definition_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_pipeline_definition_draft CHECK ((version = 0 AND status = 'DRAFT') OR (version > 0 AND status = 'PUBLISHED'))
);
