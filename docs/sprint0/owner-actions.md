# Sprint 0: remaining actions for people

There is no CI server and no GitHub CLI (owner direction, 2026-11-05). Everything is built and
tested on developer machines with `./mvnw`, and branches are merged into `main` locally. Tick each
item and record the result.

## 1. Merge the walking skeleton into `main` (S5)

On a machine with JDK 17 and Docker Desktop running:

```bash
git switch sprint0/walking-skeleton
./mvnw verify
./mvnw verify -Pslow
git switch main
git merge --no-ff sprint0/walking-skeleton
git push origin main
```

Both builds must pass before the merge. A second developer reviews the branch first
(README §13 Definition of Done).

- [ ] `verify` green · [ ] `verify -Pslow` green · [ ] merged into `main` (commit: ____)

## 2. Raise the DBA ticket (S3)

Copy [S3-dba-request.md](S3-dba-request.md) into the DBA team's queue. Ask for secret-store
references for the three logins; passwords never go in the ticket.

- [ ] Ticket ID: ____

## 3. Hold the re-estimate session (S6)

Use [S6-re-estimate.md](S6-re-estimate.md): agree the story sizes, confirm the team, accept the
architecture review dates.

- [ ] Estimates agreed · [ ] Team confirmed · [ ] Review dates accepted

## 4. Set up every developer machine

Each developer needs:
- JDK 17;
- Docker Desktop, running, for the `slow` tier (about 1.5 GB for the SQL Server 2022 image on first
  run);
- the corporate proxy in `~/.m2/settings.xml` (Maven does not read `HTTPS_PROXY`).

Each developer proves the setup with `./mvnw verify -Pslow` on `main` once.

- [ ] At least two developers can run the `slow` tier (risk R7)

## Done already

- ADR-002 and ADR-003 accepted, and the D6 regex ban signed off (2026-11-05,
  [acceptance record](G-adr-acceptance.md)).
- S4, the platform gateway PR, was dropped (2026-11-05): apiorch uses no shared gateway.
