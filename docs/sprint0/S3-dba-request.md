# S3: Database request for apiorch (draft DBA ticket)

**Status:** draft, not yet raised. The DBA team owns this.
**Stories:** US-1.1 (baseline), US-7.1 (append-only audit tables), US-7.8 (retention)
**Decision record:** [ADR-003](../adr/ADR-003-boot35-sqlserver.md)

## Request

One **SQL Server 2022** database, `apiorch`, in **dev** first, then test and prod. Settings:

| Item | Value |
|---|---|
| Collation | `SQL_Latin1_General_CP1_CI_AS` (case-insensitive identifiers, matching the H2 dev setup) |
| Compatibility level | 160 |
| Server time | Any. The application stores UTC from `SYSUTCDATETIME()` and does not depend on the server zone |
| Isolation | Default `READ COMMITTED`. `READ_COMMITTED_SNAPSHOT` stays **OFF** unless agreed with the team: the engine's row-lock design (README §5.1) is proven on the default |
| Connections | Pool of 80 per node × 3 nodes plus headroom (README §14). Please confirm the `user connections` and worker-thread settings allow about 300 |

## Logins, users and roles

| Principal | Purpose | Permissions |
|---|---|---|
| `apiorch_migrator` (login + user) | Flyway at deploy time | `db_owner` on `apiorch` only. Used by the deployment job, never by the running app |
| `apiorch_app` (login + user) | The running service | Member of role **`apiorch_app_role`** |
| `apiorch_maint` (login + user) | Retention job (partition truncate, merge and split) | Member of role **`apiorch_maint_role`** |

The roles and grants are **created by the Flyway `sqlserver/` migrations**, so they stay versioned
with the schema and Testcontainers gets the same ones. The DBA creates the logins and maps the
users to the roles:
- `apiorch_app_role`: `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the operational tables;
  **`SELECT` and `INSERT` only** on the append-only audit tables `run_event` and `step_attempt`
  (US-7.1). An `UPDATE` there must fail with error 229 (verified in ADR-003 F10).
- `apiorch_maint_role`: `ALTER` on the partitioned tables plus the partition function and scheme
  (`pf_month`, `ps_month`), for `TRUNCATE … WITH (PARTITIONS)`, `MERGE RANGE` and `SPLIT RANGE`
  (ADR-003 F9).

## Storage

- Filegroup: `PRIMARY` is acceptable for R1. The partition scheme maps all partitions to one
  filegroup.
- Sizing: planning figure ≈ 170 GB/day at full capture before purge, worst case (README §14).
  Expect much less, because `METADATA` capture is the default. US-8.2 will measure it.

## Connection details we need back

JDBC URL (`jdbc:sqlserver://<host>:1433;databaseName=apiorch;encrypt=true;trustServerCertificate=false`),
plus secret references for the three logins in the platform secret store. Passwords are never put
in tickets or config.
