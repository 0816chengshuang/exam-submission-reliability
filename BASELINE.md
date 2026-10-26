# Local Baseline

Baseline setup date: 2026-10-22. Upstream commit and license: [UPSTREAM.md](./UPSTREAM.md).

Run from this repository in PowerShell:

```powershell
.\scripts\local.ps1 -Action Build
.\scripts\local.ps1 -Action Test
.\scripts\local.ps1 -Action Start
.\scripts\check-local.ps1
.\scripts\local.ps1 -Action Status
.\scripts\local.ps1 -Action Stop
```

- Runtime: workspace-local Java 17, upstream Gradle 7.4.2 and Spring Boot 2.7.1.
- Build succeeded. Four selected tests passed: one upstream AES test and
  three synthetic H2 tests for aggregation, rollback and unique constraints.
- The launcher uses the JVM native charset for Gradle worker argument files;
  application output remains UTF-8. See the [Gradle encoding issue](https://github.com/gradle/gradle/issues/30304).
- The test entry deliberately does not run upstream SMS, cloud storage or
  developer-specific tests. Upstream `BaseTests` also prints loaded settings;
  do not load personal credentials through that fixture.
- Local API docs: http://127.0.0.1:18080/sg-user-service/swagger-ui/index.html
- Local Chinese demo: http://127.0.0.1:18080/sg-user-service/submission-demo
- Runtime PID, reports and logs are under `.run`, excluded from Git.

This is a local development baseline using H2 and the upstream embedded Redis,
not MySQL acceptance. The upstream dev SQL initializer recreates demo tables
on startup; do not put valuable data in this profile. MinIO is disabled and
the server listens only on loopback. The shutdown endpoint is enabled only
by this local launcher for graceful stopping; it is not a deployment template.
After closing the Spring context, the stop action cleans up only the recorded
JVM and its Redis child if upstream background threads keep the process alive.

Public API, rejected unauthenticated access and Redis connectivity are checked
by `check-local.ps1`. The upstream auth failure is HTTP 200 with business error
401; do not report it as HTTP 401. The H2 baseline alone does not prove MySQL
behavior. On 2026-10-25, 17 selected tests passed in both default H2 and
isolated MySQL configurations; six production-Mapper claim/recovery tests
executed against the isolated MySQL 8.0 database. A separate
synthetic HTTP run on MySQL and Redis verified login, authenticated start,
foreign-submit rejection, owner submit, duplicate rejection, stored
score/status, and ranking. See `docs/DEMO.md` for the evidence and scope.
The local launcher enables a conditional browser demo and stale async grading
scanner. The scanner reschedules committed pending records older than five
minutes; claim and grading share a new transaction, so a worker failure rolls
back to pending. Mapper rollback/reservation and scheduler behavior were
tested; actual process-crash/restart recovery and multi-instance behavior were
not. Redis rank rebuild, complete endpoint authorization audit, and index/load
testing remain outside this acceptance. The browser demo completed its five
steps in Chrome at desktop and mobile viewports using the H2 profile; the
MySQL end-to-end evidence is the separate API-script run.
The page also requires the `dev` profile and explicit demo flag. New H2/MySQL
demo schemas contain `idx_submit_recovery`; the one-time MySQL migration was
executed on the isolated synthetic demo database and confirmed by `SHOW INDEX`.
No large-table `EXPLAIN` or throughput comparison has been measured.
