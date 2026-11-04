-- H2 only: SQL Server functions missing from H2 2.3 MSSQLServer mode (ADR-003 F2, rule 3).
CREATE ALIAS IF NOT EXISTS SYSUTCDATETIME FOR 'com.crisil.apiorch.support.h2.H2Functions.sysUtcDateTime';
