# US-8.5 Hand over the production database to the DBA team
**Epic:** E8 Enablement · **Priority:** Must · **Size:** M · **Depends on:** US-7.1, US-7.8 · **Release:** R2

## User story
As a **Database Administrator** taking over apiorch's database for production, I want one handover
pack that states exactly what to provision, how to verify it and how to run it day to day, with a
script I can run as-is, so that the production database matches what the tests proved and I do
not have to reverse-engineer it from the code.

## Description
Until production there is no DBA team. Developers run SQL Server 2022 only through Testcontainers
(`./mvnw verify -Pslow`), and the Flyway migrations create every table, partition, role and grant.
This story turns that into a pack the DBA team receives when apiorch moves to production (R2):

1. **The request:** the database, its settings, the three logins and what we need back (below).
2. **A provisioning script** `ops/db/provision-apiorch.sql`, parameterised with `sqlcmd`
   variables, that creates the database, logins and users and maps users to roles. It never
   contains a password: passwords are `sqlcmd` variables filled from the secret store at run time.
3. **A verification script** `ops/db/verify-apiorch.sql` that checks every setting, role membership
   and grant, and prints one `PASS`/`FAIL` line per check.
4. **An operations section** in `docs/deployment/database.md`: deploy-time migration, backups,
   partition maintenance, index and statistics maintenance, monitoring, and capacity.

The roles and grants themselves stay in the Flyway `db/migration/sqlserver/` migrations (US-7.1),
so they are versioned with the schema and tested on every `slow` run. The DBA creates logins and
role memberships only.

### What the DBA team provisions

**Database `apiorch`, SQL Server 2022**, one per environment (test, then prod):

| Setting | Value | Why |
|---|---|---|
| Collation | `SQL_Latin1_General_CP1_CI_AS` | Case-insensitive identifiers, matching the H2 development setup |
| Compatibility level | 160 | Matches the SQL Server 2022 image the tests run on |
| Server time zone | Any | The app stores UTC from `SYSUTCDATETIME()` and never depends on the server zone |
| `READ_COMMITTED_SNAPSHOT` | **OFF** | The engine's row-lock design (README §5.1) is proven on default `READ COMMITTED` only |
| Recovery model | `FULL` | RPO 0 for run state (README §14) |
| Connections | About 300: a pool of 80 per node × 3 nodes plus headroom (README §14) | Confirm `user connections` and worker threads allow it |
| Filegroup | `PRIMARY` is acceptable; all partitions of `ps_month` map to one filegroup | Simplicity; revisit if US-8.2 sizing demands it |

**Logins and users:**

| Principal | Used by | Permissions |
|---|---|---|
| `apiorch_migrator` | Flyway at deploy time only, never the running service | `db_owner` on `apiorch` only |
| `apiorch_app` | The running service | Member of `apiorch_app_role` |
| `apiorch_maint` | The retention and partition jobs (US-7.8) | Member of `apiorch_maint_role` |

**Roles (created by Flyway, not by the DBA):**
- `apiorch_app_role`: `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the operational tables; **only
  `SELECT` and `INSERT`** on the append-only `run_event` and `step_attempt` (US-7.1). An `UPDATE`
  there fails with error 229 (ADR-003 F10).
- `apiorch_maint_role`: `ALTER` on the partitioned tables and on `pf_month` / `ps_month`, for
  `TRUNCATE … WITH (PARTITIONS)`, `MERGE RANGE` and `SPLIT RANGE` (ADR-003 F9, US-7.8).

**What we need back:** the JDBC URL
(`jdbc:sqlserver://<host>:1433;databaseName=apiorch;encrypt=true;trustServerCertificate=false`),
secret-store references for the three logins, the backup schedule, and the agreed RPO/RTO.

**Out of scope:** high-availability topology (Always On, failover cluster) beyond recording the
DBA team's choice; it is their decision. Encryption at rest beyond what the platform provides
(working context is already AES-GCM encrypted by the app, US-7.6).

## Acceptance criteria
- [ ] **AC1 (provisioning script):** Given an empty SQL Server 2022 instance and the three passwords
  passed as `sqlcmd` variables, when `ops/db/provision-apiorch.sql` runs, then the `apiorch`
  database exists with every setting in the table above, the three logins and users exist, and
  running it a second time changes nothing and fails on nothing (idempotent).
- [ ] **AC2 (migrations by the migrator only):** Given a provisioned database, when Flyway runs as
  `apiorch_migrator`, then every migration applies and `apiorch_app_role` and `apiorch_maint_role`
  exist with the grants above; and when the service starts as `apiorch_app`, then it passes
  readiness without any DDL permission.
- [ ] **AC3 (least privilege):** Given the service connected as `apiorch_app`, when it attempts an
  `UPDATE` or `DELETE` on `run_event` or `step_attempt`, or any `CREATE`/`ALTER`, then SQL Server
  rejects it with error 229 or 262; and `apiorch_maint` can `SPLIT RANGE` on `pf_month` but cannot
  read or write `run_context`.
- [ ] **AC4 (verification):** Given a provisioned and migrated database, when
  `ops/db/verify-apiorch.sql` runs, then it prints one `PASS` line per check (collation,
  compatibility level, `READ_COMMITTED_SNAPSHOT` off, recovery model, role memberships, the
  append-only grants, partitions for the current and next month) and exits non-zero on any `FAIL`.
- [ ] **AC5 (no secrets):** Given the handover pack and both scripts, then none of them contains a
  password, connection secret or real host name; a test scans the files and fails on anything that
  looks like a password assignment.
- [ ] **AC6 (operations guide):** Given `docs/deployment/database.md`, then it covers: who runs
  Flyway and when (deploy job, `apiorch_migrator`); backup and restore, with a restore test before
  go-live; the US-7.8 partition and retention jobs and how to check they ran; index and statistics
  maintenance; monitoring for blocking, lock timeouts (error 1222), log growth and the default
  partition filling; and the capacity figure measured by US-8.2.
- [ ] **AC7 (handover):** Given the move to production, when the pack is handed over, then the DBA
  team's ticket references this story, and the verification output from the production database is
  attached to the go-live checklist (US-8.4).

## Technical details
**Files**
- `ops/db/provision-apiorch.sql`: `sqlcmd` variables `$(APIORCH_DB)`, `$(MIGRATOR_PASSWORD)`,
  `$(APP_PASSWORD)`, `$(MAINT_PASSWORD)`. Guards every `CREATE` with `IF NOT EXISTS` for AC1.
  Settings: `ALTER DATABASE … SET READ_COMMITTED_SNAPSHOT OFF`, `SET RECOVERY FULL`,
  `SET COMPATIBILITY_LEVEL = 160`. Role memberships are added **after** Flyway has created the roles,
  in a second section the script runs when they exist.
- `ops/db/verify-apiorch.sql`: reads `sys.databases`, `sys.database_role_members`,
  `sys.database_permissions` and `sys.partition_range_values`; `RAISERROR` with severity 16 on any
  failure so `sqlcmd -b` exits non-zero.
- `docs/deployment/database.md`: the request tables above plus the operations guide (AC6).

**Run order at go-live:** provision (section 1) → Flyway as `apiorch_migrator` → provision
(section 2, role memberships) → verify → start the service as `apiorch_app`.

**Configuration:** the service uses `spring.datasource.*` with `apiorch_app`; Flyway uses
`spring.flyway.user` / `spring.flyway.password` with `apiorch_migrator` in the deploy job only; the
maintenance jobs use a second datasource as `apiorch_maint` (US-7.8). All passwords come from the
secret store.

**Test notes**
- `ProvisioningScriptIT` (`slow`): runs both script sections against a Testcontainers SQL Server
  2022 with `sqlcmd` inside the container, runs Flyway as `apiorch_migrator`, starts a context as
  `apiorch_app`, and asserts AC1 (including a second run), AC2 and AC3.
- `VerifyScriptIT` (`slow`): asserts all `PASS` on a correct database, and a `FAIL` plus a non-zero
  exit after turning `READ_COMMITTED_SNAPSHOT` on (AC4).
- `HandoverSecretsScanTest`: scans `ops/db/` and `docs/deployment/database.md` for password-like
  assignments (AC5).
- AC6 and AC7 are checked by review at the R2 go-live readiness review.
