# Sprint 0: actions for the repo owner and other teams

Everything below needs a person: a GitHub sign-in, another team's repository or the DBA's ticket
queue. Do them in this order. Tick each one and record the link.

## 1. Open the Sprint 0 PR (S5, S2)

1. Open <https://github.com/sarvesh-belose/api-pipeline/compare/main...sprint0/walking-skeleton>
   and choose **Create pull request**.
   - Title: `Sprint 0: walking skeleton, CI tiers, PR template, DBA and gateway drafts`
   - The description is filled from `.github/pull_request_template.md`. Tick the Definition of
     Done items.
2. Wait for the **`verify (fast + component)`** check to go green. That is the first CI run on
   GitHub (exit condition "CI green on the skeleton").
3. Start the SQL Server tier once by hand: **Actions → ci → Run workflow**, branch
   `sprint0/walking-skeleton`, input `slow` = `true`. It needs about 5 minutes, most of it pulling
   the SQL Server image.
4. Merge, using squash or merge commit, and delete the branch.

- [ ] PR link: ____
- [ ] `verify` green · [ ] `slow` green

## 2. Protect `main` (S1)

**Settings → Branches → Add branch ruleset** (or **Add classic branch protection rule**) for
`main`:

| Setting | Value |
|---|---|
| Require a pull request before merging | On, **1** approval, dismiss stale approvals |
| Require review from Code Owners | On (`CODEOWNERS`) |
| Require status checks to pass | On: **`verify (fast + component)`**, and require the branch to be up to date |
| Block force pushes | On |
| Restrict deletions | On |
| Do not allow bypassing | On once a second maintainer exists. A solo owner can't approve their own PR, so leave it off until then |

Do this **after** step 1 has run CI once, so the check name appears in the list.

- [ ] Branch protection on

## 3. Raise the platform gateway PR (S4)

Target `parasdshah/workflow-user-stories`, file `api-gateway/src/main/resources/application.yml`.

1. Fork the repository, or create a branch if you have write access. A suggested name is
   `apiorch/gateway-route`.
2. Paste the route from [`ops/gateway/apiorch-route.yml`](../../ops/gateway/apiorch-route.yml)
   **between the `hrms-service` route and the `workflow-service` `/api/**` catch-all**.
3. Open the PR with the text from [S4-gateway-route-request.md](S4-gateway-route-request.md),
   sections "Change" to "Verification after merge".
4. Record the link in the R1 plan S4 row. Exit criterion R4 stays open until it is **merged**.

- [ ] Gateway PR link: ____

## 4. Raise the DBA ticket (S3)

Copy [S3-dba-request.md](S3-dba-request.md) into the DBA team's queue. Ask for secret-store
references for the three logins; passwords never go in the ticket.

- [ ] Ticket ID: ____

## 5. Hold the Sprint 0 review (G1, G2, S6)

- ADR acceptance: [G-adr-acceptance.md](G-adr-acceptance.md). Four decisions, including Boot 3.5
  support.
- Re-estimate, team and review dates: [S6-re-estimate.md](S6-re-estimate.md).

- [ ] ADR-002 and ADR-003 accepted · [ ] D6 signed off · [ ] Estimates agreed · [ ] Team confirmed

## Optional: let Claude Code do steps 1–3 next time

Install the GitHub CLI and sign in once. After that, PRs and branch settings can be scripted from
this repository.

```bash
winget install GitHub.cli
gh auth login
```
