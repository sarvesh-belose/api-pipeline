-- SQL Server: monthly partitioning (README §10), JSON validity check, and the US-7.1 append-only grant model.
DECLARE @m DATE = DATEFROMPARTS(YEAR(SYSUTCDATETIME()), MONTH(SYSUTCDATETIME()), 1);
DECLARE @sql NVARCHAR(MAX) = N'CREATE PARTITION FUNCTION pf_month (DATETIME2(6)) AS RANGE RIGHT FOR VALUES ('''
    + CONVERT(NVARCHAR(10), @m, 23) + N''', ''' + CONVERT(NVARCHAR(10), DATEADD(MONTH, 1, @m), 23) + N''', '''
    + CONVERT(NVARCHAR(10), DATEADD(MONTH, 2, @m), 23) + N''')';
EXEC sp_executesql @sql;
GO
CREATE PARTITION SCHEME ps_month AS PARTITION pf_month ALL TO ([PRIMARY]);
GO
CREATE TABLE run_event (
  run_id UNIQUEIDENTIFIER NOT NULL,
  seq BIGINT NOT NULL,
  occurred_at DATETIME2(6) NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  details NVARCHAR(MAX),
  CONSTRAINT pk_run_event PRIMARY KEY CLUSTERED (run_id, seq, occurred_at) ON ps_month (occurred_at),
  CONSTRAINT ck_run_event_details_json CHECK (details IS NULL OR ISJSON(details) = 1)
);
GO
CREATE ROLE apiorch_app;
GRANT SELECT, INSERT, UPDATE ON pipeline_run TO apiorch_app;
GRANT SELECT, INSERT ON run_event TO apiorch_app;
CREATE USER probe_app WITHOUT LOGIN;
ALTER ROLE apiorch_app ADD MEMBER probe_app;
