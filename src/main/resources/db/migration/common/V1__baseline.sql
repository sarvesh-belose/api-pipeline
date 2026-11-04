-- US-1.1 baseline. SQL Server syntax; runs unchanged on H2 in MSSQL mode (ADR-003 rule 1).
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until DATETIME2(6) NOT NULL,
  locked_at  DATETIME2(6) NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
