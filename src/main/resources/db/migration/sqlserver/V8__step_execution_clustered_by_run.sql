-- US-4.4 (found by AdvanceRaceSqlServerIT): step_execution was clustered on its random id, so each run's
-- "WHERE run_id = ?" read scanned other runs' rows and deadlocked with their updates. Clustering on
-- (run_id, step_id) keeps every run's reads and writes on its own rows. SQL Server only: H2 uses MVCC.
DECLARE @pk sysname = (SELECT name FROM sys.key_constraints
                       WHERE parent_object_id = OBJECT_ID('step_execution') AND type = 'PK');
EXEC ('ALTER TABLE step_execution DROP CONSTRAINT ' + @pk);
GO
ALTER TABLE step_execution DROP CONSTRAINT uq_step_execution_run_step;
GO
ALTER TABLE step_execution ADD CONSTRAINT uq_step_execution_run_step UNIQUE CLUSTERED (run_id, step_id);
GO
ALTER TABLE step_execution ADD CONSTRAINT pk_step_execution PRIMARY KEY NONCLUSTERED (id);
