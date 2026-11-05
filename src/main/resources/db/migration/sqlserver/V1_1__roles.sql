-- US-7.1 / US-8.5: the database roles, created by Flyway so they are versioned with the schema and present in every
-- Testcontainers database. The DBA creates logins and maps users to these roles; nothing here names a login.
-- The application role gets data access on the schema; append-only tables DENY UPDATE and DELETE in their own
-- migration (V5, V6, V7), and DENY wins over the schema grant (ADR-003 F10). H2 has no grants, so no counterpart.
CREATE ROLE apiorch_app_role;
CREATE ROLE apiorch_maint_role;
GO
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::dbo TO apiorch_app_role;
GRANT EXECUTE ON SCHEMA::dbo TO apiorch_app_role;
-- Partition MERGE and SPLIT need ALTER ANY DATASPACE; TRUNCATE ... WITH (PARTITIONS) needs ALTER on the table,
-- granted per partitioned table (US-7.8, ADR-003 F9).
GRANT ALTER ANY DATASPACE TO apiorch_maint_role;
