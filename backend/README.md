# CBRMS (Phase 1 backend skeleton)

TIRDO Central Billing and Revenue Management System — the shared service that issues GePG bills for TIRDO platforms and records verified payments exactly once.

Built from the CBRMS planning package v2.1: Blueprint (02), SRS (04), Architecture (06) and Database Design (07).

**Stack:** Java 21, Spring Boot 4.0.x, PostgreSQL 18, Flyway, Spring Security (Keycloak JWT in production), JdbcClient (no ORM on the financial path).

## What works now

| Capability | Requirements | Where |
|---|---|---|
| Create a bill, priced from the approved catalogue; the client never sends amounts | FR01–FR06, BR01 | `billing/BillService` |
| Idempotency-Key handling and one active bill per liability | FR04, BR03, AT02 | `BillService`, index `bill_one_active_liability` |
| Maker-checker approval (service rule plus database constraint) | FR07, BR08, AT06 | `billing/ApprovalService` |
| Submission to GePG with a stable bill ID; lost response becomes AckUnknown, never a second bill | FR08 | `gepg/GepgSubmissionWorker` |
| Control number stored only after an accepted result (invariant I2, checked at commit) | FR09 | `GepgInboundService`, `V6__invariants.sql` |
| Signed inbound messages; invalid signatures have no financial effect | FR12, AT08 | `GepgInboundService`, `MessageVerifier` |
| One posting per GePG transaction across message types; inbox deduplication | FR13, AT13 | `payments/PaymentService` |
| Exact, Partial and Full payment modes; excess held as credit; mismatches open cases | FR15, FR16, AT03, AT10 | `domain/AllocationRules` |
| Unmatched payments to suspense; never matched by name or amount | FR14 | `PaymentService` |
| Transactional outbox, HMAC-signed delivery, backoff, failure cases, recovery cursor | FR17, FR23–FR25, FR36 | `events/*` |
| Escalation cases for AckUnknown (30 min) and missing control numbers (60 min) | SOP-02 | `gepg/GepgEscalationMonitor` |
| Append-only payments, allocations, inbox, outbox, bill lines and audit | FR18, FR28 | `V1`–`V5` triggers |
| Invariant and ledger-drift view for the nightly consistency job | I1–I7 | `v_invariant_violations` |
| GePG simulator: control numbers and payments through the same signed path | Doc 08 §2 | `gepg/sim/*` |
| Cancellation with maker-checker, GePG confirmation, then a replacement bill for the same liability | FR10, FR11, AT16 | `billing/BillChangeService`, `BillService.checkReplaceable` |
| Expiry change with maker-checker and GePG confirmation | FR33, AT20 | `BillChangeService`, `GepgInboundService.receiveChangeResult` |
| Daily reconciliation: GePG feed matched by transaction ID; every difference opens a case; re-runs on unchanged data reuse the batch; officer sign-off | FR19–FR22, FR34, AT05 | `reconciliation/ReconciliationService` |
| Refunds from client credit: request, second-officer approval, bank reference or failure case | AT10, I7 | `refunds/RefundService` |
| Client notices in Kiswahili and English (control number, payment, cancellation, new deadline) with retry | FR31 | `notifications/*` |
| Public bill lookup with control number plus SMS one-time code, rate limits and lockout | FR32, AT19 | `lookup/PublicLookupController` |
| Sign-in mode by setting: Keycloak JWT, or development users (refused unless the simulator is on) | NFR security | `config/SecurityConfig` |
| JWT audience check: only tokens issued for CBRMS are accepted | NFR security | `spring.security.oauth2.resourceserver.jwt.audiences` |
| Several platforms (LIMS, CIAP, TeLTP, ISICMS). Each bills only its mapped services and cannot see or change another platform's bills | FR26 | `source_system_service`, `BillController`, `BillChangeService` |
| Platform list and per-platform bill filter for Finance | FR35 | `GET /internal/v1/admin/platforms`, `?source=` on `/admin/bills` |
| Collections report by platform, service or GFS code for a date range: received = applied + credit + suspense. Multi-line bills are shared across lines in proportion to line totals, to the cent | BR reporting | `reports/ReportService`, `GET /internal/v1/admin/reports/collections[.csv]` |
| Outstanding balances aged by days past due (not yet due, 1–30, 31–60, 61–90, over 90), with every bill in the CSV | BR reporting | `GET /internal/v1/admin/reports/outstanding[.csv]` |
| CSV export: UTF-8 with BOM for Excel, formula-injection safe, each export audited | FR28 | `reports/ReportsController` |
| Catalogue maintenance: new services, new price versions, new revenue codes and settings (status, platforms, approval rule), each proposed by Finance and applied only after a second officer approves. Prices are never backdated; the replaced version closes the day before. Platforms read their current versions at `GET /internal/v1/catalogue` | FR01, FR02, AT01 | `catalogue/CatalogueService`, `V9__catalogue_changes.sql` |
| Corrections under maker-checker: move unallocated money (suspense or freed by a reversal) to a bill; reverse a payment's allocation (original kept, platform notified, review case if the service was already released). Each applied correction records a financial adjustment | FR18, FR21, AT14 | `payments/CorrectionService`, `V10__corrections.sql` |
| Case work: take a case, close it with a resolution; unallocated-money cases need an evidence reference | FR21 | `cases/CaseWorkService` |
| Audit search by person, action, record, date and correlation ID, with audited CSV export | FR28 | `audit/AuditSearchController` |
| FINANCE_VIEWER is read-only: every POST under `/internal/v1/admin` needs BILLING_OFFICER or BILL_APPROVER | NFR06 | `config/SecurityConfig` |
| Quoted services (CIAP consultancy): platform submits a quotation with milestones, Finance approves (maker-checker), bills price each milestone from the quotation, at most once. Quotation terms and milestones are frozen by database triggers | FR01, FR03, FR07 | `billing/QuotationService`, `V8__quotations.sql` |

## Next increments (not yet built)

1. **Contract GePG adapter.** This needs the specification, certificates and endpoints assigned to TIRDO. `ContractGepgGateway` refuses to start until it is implemented, so production cannot run on a half-built adapter.
2. **SMS and email gateway adapter.** It implements `notifications/MessageSender` once TIRDO's SMS provider is chosen. Until then, `LoggingMessageSender` records messages without sending them.
3. **Bank statement import** for reconciliation, alongside the GePG feed.
4. Reversals (AT14) and accounting export (FR30).
5. CI pipeline and the nightly invariant job.

## Verification of the change, refund, reconciliation, notice and lookup increment

- V7 and the SSO seed were applied on top of V1–V6 to a fresh PostgreSQL database.
- All 144 SQL statements in the Java code were prepared against that schema.
- Main and test code type-check.
- `FinanceOperationsIntegrationTest` covers the following. Your next `mvn verify` runs it against the real Spring jars:
  - cancel then replace
  - cancellation refused after payment
  - expiry change
  - reconciliation, including a re-run
  - refund with self-approval refused
  - notices and the one-time code lookup

## Verification done before handover (first increment)

- **Migrations:** every Flyway migration and the seed data were applied to a fresh PostgreSQL database. SQL tests confirmed:
  - each constraint and trigger rejects what it should
  - the replacement-bill path works
  - the invariant view flags ledger drift
- **SQL in the code:** all 60 statements in the Java code were checked against the migrated schema with `EXPLAIN`.
- **Domain logic:** rounding, allocation, backoff, HMAC and JSON were compiled and tested (22 unit tests pass).
- **Spring layer:** written for Spring Boot 4.0 and type-checked. It has **not yet been compiled against the real Spring jars**, because the build environment could not reach Maven Central. Your first `mvn verify` is that check. If it reports an API mismatch, send me the error.

## Run it on Windows with Docker (PowerShell)

The Compose file is one level up, in `C:\dev\cbrms`, and starts the database, this backend and the Finance console together.

Prerequisite: Docker Desktop. You don't need a local JDK or Maven, because the build runs inside Docker.

Build the image and start PostgreSQL 18 and CBRMS (port 8085). The first build downloads dependencies and takes a few minutes:

```powershell
cd C:\dev\cbrms; docker compose up -d --build; docker compose logs -f cbrms
```

Press Ctrl+C to stop following the logs; the containers keep running.

The database has no host port by default, so it cannot clash with other PostgreSQL servers on your laptop. To open it in pgAdmin or DBeaver (`localhost:55432`, database `cbrms`, user and password `cbrms`):

```powershell
cd C:\dev\cbrms; $env:CBRMS_PG_PORT = "55432"; docker compose -f docker-compose.yml -f docker-compose.db-port.yml up -d
```

If port 8085 is taken, set `$env:CBRMS_PORT` to another port before `docker compose up`.

Run all tests, including the integration tests against the `cbrms_test` database, in a Maven container on the same Docker network:

```powershell
cd C:\dev\cbrms\backend; docker run --rm -v "${PWD}:/src" -v cbrms-m2:/root/.m2 -w /src --network cbrms-net -e CBRMS_TEST_DB_URL=jdbc:postgresql://cbrms-postgres:5432/cbrms_test maven:3.9-eclipse-temurin-21 mvn -B verify
```

Rebuild after code changes:

```powershell
cd C:\dev\cbrms; docker compose up -d --build cbrms
```

Stop the stack (add `-v` to also delete the database volume):

```powershell
cd C:\dev\cbrms; docker compose down
```

### Walk through a bill in a second PowerShell window

```powershell
$base = "http://localhost:8085"
$lims = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("lims:lims-dev-secret")) }

# 1. LIMS requests a bill (2 water samples at TZS 45,000)
$body = @{
  sourceReference = "LAB-2026-0001"
  payer = @{ reference = "CUST-001"; name = "Test Client Ltd"; phone = "255700000000"; email = "client@example.com" }
  currency = "TZS"; description = "Water sample analysis"; validityDays = 30
  lines = @(@{ serviceCode = "LIMS-CHEM-001"; tariffVersion = 1; quantity = 2 })
} | ConvertTo-Json -Depth 5
$bill = Invoke-RestMethod -Method Post -Uri "$base/internal/v1/bills" -Headers ($lims + @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }) -ContentType "application/json" -Body $body
$bill | Format-List billId, total, preparationState, externalRegistrationStatus

# 2. Wait for submission and the simulated control number
Start-Sleep -Seconds 8
$bill = Invoke-RestMethod -Uri "$base/internal/v1/bills/$($bill.billId)" -Headers $lims
$bill | Format-List controlNumber, externalRegistrationStatus

# 3. Pay it through the simulator, then check the bill
Invoke-RestMethod -Method Post -Uri "$base/sim/v1/payments" -ContentType "application/json" -Body (@{ controlNumber = $bill.controlNumber; amount = 90000 } | ConvertTo-Json)
Invoke-RestMethod -Uri "$base/internal/v1/bills/$($bill.billId)" -Headers $lims | Format-List collectionState, releaseState

# 4. Read the event feed LIMS would receive
(Invoke-RestMethod -Uri "$base/internal/v1/events?after=0" -Headers $lims).events | ForEach-Object { $_.event.type }
```

### Try maker-checker

`LIMS-MICRO-002` requires approval. The platform cannot approve its own bill, but a Finance approver can:

```powershell
$approver = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("finance.approver:approver-dev")) }
$body = @{ sourceReference = "LAB-2026-0002"; payer = @{ reference = "CUST-002"; name = "Another Client" }; currency = "TZS"; description = "Microbiology"; lines = @(@{ serviceCode = "LIMS-MICRO-002"; tariffVersion = 1; quantity = 1 }) } | ConvertTo-Json -Depth 5
$b2 = Invoke-RestMethod -Method Post -Uri "$base/internal/v1/bills" -Headers ($lims + @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }) -ContentType "application/json" -Body $body
Invoke-RestMethod -Method Post -Uri "$base/internal/v1/bills/$($b2.billId)/approval" -Headers $approver -ContentType "application/json" -Body (@{ decision = "APPROVED"; reason = "Checked" } | ConvertTo-Json) | Format-List preparationState
```

## Configuration

| Setting | Purpose |
|---|---|
| `CBRMS_DB_URL`, `CBRMS_DB_USER`, `CBRMS_DB_PASSWORD` | Database connection |
| `CBRMS_OIDC_ISSUER` | Keycloak realm issuer URI (profile `prod`) |
| `CBRMS_SECURITY_MODE` | `jwt` (Keycloak; the default) or `basic` (development users; refused unless `cbrms.gepg.mode=simulator`) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, `..._JWK_SET_URI` | Where the token issuer is and where to fetch its keys (set by `docker-compose.sso.yml` locally) |
| `CBRMS_JWT_AUDIENCE` | Audience that tokens must carry in jwt mode (default `cbrms`; set by an audience mapper on each Keycloak client) |
| `CBRMS_LOOKUP_PEPPER` | Secret mixed into stored one-time-code hashes. Set a long random value in production |
| `cbrms.reconciliation.cron` | When the daily reconciliation runs (default 06:30, Dar es Salaam time) |
| `cbrms.notifications.sender` | `log` records notices without sending. Replace it with the gateway adapter |
| `CBRMS_GEPG_PROVIDER` | `gepg_provider.code` for this deployment |
| `cbrms.webhook-keys.[keyId]` | HMAC keys for platform event delivery. Supply them from the secret store, never from Git |
| `cbrms.gepg.mode` | `simulator` (local and test) or `contract` (production adapter) |

**Profiles:**
- `local`: simulator, development users, seed data.
- `test`: same as `local`, with schedulers off.
- `prod`: JWT, contract adapter, no seed data.

Never enable `local` or `test` in production. They switch on HTTP Basic users with known passwords and the payment simulator.

## Layout

```
src/main/java/tz/go/tirdo/cbrms/
  domain/       pure rules: rounding, allocation, states, backoff (no Spring)
  billing/      create, view, approve bills; idempotency
  gepg/         ports, inbound processing, submission worker, escalation; sim/ and contract/ adapters
  payments/     exactly-once payment posting
  events/       outbox, signed dispatcher, recovery feed
  catalogue/    tariff resolution
  cases/ audit/ identity/ config/ common/
  reconciliation/ refunds/ notifications/ lookup/ reports/ admin/
src/main/resources/db/migration   V1–V11 (PostgreSQL 18; uuidv7() shim for 13–17)
src/main/resources/db/seed        development seed (local and test profiles only)
```
