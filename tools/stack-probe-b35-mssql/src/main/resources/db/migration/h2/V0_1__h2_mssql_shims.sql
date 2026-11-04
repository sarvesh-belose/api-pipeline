-- H2 only: SQL Server functions missing from H2 2.3 MSSQLServer mode (ADR-003 finding).
CREATE ALIAS IF NOT EXISTS SYSUTCDATETIME FOR 'probe.H2Functions.sysUtcDateTime';
