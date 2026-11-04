-- Portable between SQL Server and H2 (MSSQL mode): one file for both.
CREATE TABLE shedlock (
  name VARCHAR(64) NOT NULL PRIMARY KEY,
  lock_until DATETIME2(3) NOT NULL,
  locked_at DATETIME2(3) NOT NULL,
  locked_by VARCHAR(255) NOT NULL
);

CREATE TABLE pipeline_run (
  id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME2(6) NOT NULL DEFAULT SYSUTCDATETIME(),
  owner_instance VARCHAR(128),
  lease_epoch BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME2(6),
  wakeup_requested BIT NOT NULL DEFAULT 0,
  event_seq BIGINT NOT NULL DEFAULT 0,
  meta NVARCHAR(MAX)
);
