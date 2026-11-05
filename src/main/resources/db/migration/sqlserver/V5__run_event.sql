-- US-7.1 (schema-ahead). The shared monthly partition function and scheme (README §10, ADR-003 F9), then run_event.
-- RANGE RIGHT boundaries on the first day of the current month and the next three, from database time, so the
-- current month and each of the next three get a partition of their own. PartitionMaintenanceJob (US-7.8) adds
-- later months. The first and last partitions are expected to stay empty.
DECLARE @m DATE = DATEFROMPARTS(YEAR(SYSUTCDATETIME()), MONTH(SYSUTCDATETIME()), 1);
DECLARE @sql NVARCHAR(MAX) = N'CREATE PARTITION FUNCTION pf_month (DATETIME2(6)) AS RANGE RIGHT FOR VALUES ('''
    + CONVERT(NVARCHAR(10), @m, 23) + N''', '''
    + CONVERT(NVARCHAR(10), DATEADD(MONTH, 1, @m), 23) + N''', '''
    + CONVERT(NVARCHAR(10), DATEADD(MONTH, 2, @m), 23) + N''', '''
    + CONVERT(NVARCHAR(10), DATEADD(MONTH, 3, @m), 23) + N''')';
EXEC sp_executesql @sql;
GO
CREATE PARTITION SCHEME ps_month AS PARTITION pf_month ALL TO ([PRIMARY]);
GO
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
  CONSTRAINT pk_run_event PRIMARY KEY CLUSTERED (run_id, seq, occurred_at) ON ps_month (occurred_at),
  CONSTRAINT ck_run_event_details_json CHECK (details IS NULL OR ISJSON(details) = 1)
);
GO
-- Append-only for the application (US-7.1 AC7): an UPDATE or DELETE fails with error 229.
DENY UPDATE, DELETE ON run_event TO apiorch_app_role;
GRANT SELECT, DELETE, ALTER ON run_event TO apiorch_maint_role;
