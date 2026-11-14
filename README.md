# CBRMS

TIRDO Central Billing and Revenue Management System: the shared service that issues GePG bills for TIRDO platforms (LIMS, CIAP, TeLTP and ISICMS) and records verified payments exactly once.

| Path | Contents |
|---|---|
| `backend/` | Spring Boot 4 / Java 21 service, Flyway schema for PostgreSQL 18, GePG simulator. See `backend/README.md`. |
| `frontend/` | Angular 22 Finance console, served by nginx. See `frontend/README.md`. |
| `sso/tirdo-realm.json` | Keycloak realm for local single sign-on: roles, the console, the LIMS, CIAP, TeLTP and ISICMS clients, and two Finance users. |
| `docker-compose.yml` | The local stack: PostgreSQL, backend and console. Sign-in uses fixed development users. |
| `docker-compose.sso.yml` | Overlay that adds Keycloak and switches the backend and console to single sign-on. |
| `docker-compose.db-port.yml` | Optional overlay that publishes the database on port 55432. |
| `docker-compose.ops.yml` | Optional monitoring overlay: Prometheus, Alertmanager and Grafana. |
| `docker-compose.backup.yml` | Optional backup overlay: PostgreSQL with pgBackRest and WAL archiving every 60 seconds. |
| `ops/` | Prometheus configuration and alert rules (with unit tests), Alertmanager, the Grafana dashboard and `RUNBOOKS.md`. |

## Start everything (PowerShell)

### Option A: development users (no Keycloak)

```powershell
cd C:\dev\cbrms; docker compose up -d --build
```

- Console: http://localhost:8086. Sign in as `finance.approver` / `approver-dev` (can approve), `finance.maker` / `maker-dev`, or `internal.auditor` / `auditor-dev` (Internal Audit: sees everything, changes nothing).
- Backend API: http://localhost:8085. The LIMS development client is `lims` / `lims-dev-secret` (HTTP Basic).

### Option B: Keycloak single sign-on

```powershell
cd C:\dev\cbrms; docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d --build
```

- Console: http://localhost:8086, then **Sign in with TIRDO account**. The users are the same (`finance.maker` / `maker-dev`, `finance.approver` / `approver-dev`), but now Keycloak checks them.
- Keycloak admin: http://localhost:8190, user `admin` / `admin`, realm `tirdo`. The first start takes about a minute.
- The backend now accepts only Keycloak tokens. Basic sign-in is refused.
- **Staff sign in with an authenticator app (MFA).** At the first sign-in Keycloak shows a QR code: scan it with Microsoft Authenticator, Google Authenticator or FreeOTP and type the 6-digit code. After that each sign-in asks for a code. The backend refuses staff rights to tokens without MFA. A lost phone is handled in RB-08 (`ops/RUNBOOKS.md`).
- Rotate a platform's secret with `.\sso\Rotate-ClientSecret.ps1 -Client lims` (RB-09). The old secret stops working at once.
- Each platform signs in with client credentials. CBRMS accepts only tokens issued for it (audience `cbrms`):

| Platform | Client ID | Development secret | Services it may bill |
|---|---|---|---|
| LIMS | `lims` | `lims-sso-dev-secret` | `LIMS-CHEM-001`, `LIMS-MICRO-002` |
| CIAP | `ciap` | `ciap-sso-dev-secret` | `CIAP-ADV-001`, `CIAP-REV-002` (needs approval) |
| TeLTP | `teltp` | `teltp-sso-dev-secret` | `TELTP-CRS-001` |
| ISICMS | `isicms` | `isicms-sso-dev-secret` | `ISICMS-INC-001`, `ISICMS-LAB-002` (needs approval) |

The services and prices are development placeholders. In Option A, the same platforms use HTTP Basic: `ciap` / `ciap-dev-secret`, and so on.

This gets a token as CIAP and requests a bill for two advisory days:

```powershell
$tok = (Invoke-RestMethod -Method Post -Uri "http://localhost:8190/realms/tirdo/protocol/openid-connect/token" -ContentType "application/x-www-form-urlencoded" -Body @{ grant_type = "client_credentials"; client_id = "ciap"; client_secret = "ciap-sso-dev-secret" }).access_token; $body = @{ sourceReference = "CIAP-2026-0001"; payer = @{ reference = "CUST-901"; name = "Tanga Cement PLC"; phone = "255700000001" }; currency = "TZS"; description = "Industrial advisory, 2 days"; language = "en"; lines = @(@{ serviceCode = "CIAP-ADV-001"; tariffVersion = 1; quantity = 2 }) } | ConvertTo-Json -Depth 5; Invoke-RestMethod -Method Post -Uri "http://localhost:8085/internal/v1/bills" -Headers @{ Authorization = "Bearer $tok"; "Idempotency-Key" = [guid]::NewGuid().ToString() } -ContentType "application/json" -Body $body | Format-List sourceSystem, sourceReference, total, preparationState
```

### Quoted consultancy (CIAP)

Consultancy prices are agreed per engagement, so CIAP bills `CIAP-CONS-003` from a quotation rather than the catalogue:

1. CIAP submits the quotation with its payment milestones.
2. A bill approver approves it under **Approvals** (or **Quotations**) in the console.
3. CIAP bills one milestone at a time by number. CBRMS takes the amount from the quotation, and each milestone can be billed only once.

This submits a two-milestone quotation as CIAP:

```powershell
$tok = (Invoke-RestMethod -Method Post -Uri "http://localhost:8190/realms/tirdo/protocol/openid-connect/token" -ContentType "application/x-www-form-urlencoded" -Body @{ grant_type = "client_credentials"; client_id = "ciap"; client_secret = "ciap-sso-dev-secret" }).access_token; $q = "CIAP-Q-" + (Get-Random); $body = @{ quoteReference = $q; payer = @{ reference = "CUST-901"; name = "Tanga Cement PLC"; phone = "255700000001" }; serviceCode = "CIAP-CONS-003"; description = "Energy audit of the kiln line"; validUntil = (Get-Date).AddMonths(6).ToString("yyyy-MM-dd"); language = "en"; milestones = @(@{ description = "Inception report"; amount = 4000000 }, @{ description = "Final report"; amount = 6000000 }) } | ConvertTo-Json -Depth 5; Invoke-RestMethod -Method Post -Uri "http://localhost:8085/internal/v1/quotations" -Headers @{ Authorization = "Bearer $tok" } -ContentType "application/json" -Body $body | Format-List quoteReference, total, status; "Quote reference: $q"
```

Approve it in the console as `finance.approver`. Then, in the same PowerShell window, bill milestone 1:

```powershell
$body = @{ sourceReference = "$q-M1"; payer = @{ reference = "CUST-901"; name = "Tanga Cement PLC"; phone = "255700000001" }; currency = "TZS"; description = "Energy audit: inception report"; language = "en"; lines = @(@{ serviceCode = "CIAP-CONS-003"; tariffVersion = 1; quantity = 1; quotationReference = $q; milestone = 1 }) } | ConvertTo-Json -Depth 5; Invoke-RestMethod -Method Post -Uri "http://localhost:8085/internal/v1/bills" -Headers @{ Authorization = "Bearer $tok"; "Idempotency-Key" = [guid]::NewGuid().ToString() } -ContentType "application/json" -Body $body | Format-List sourceReference, total, preparationState
```

The bill total is TZS 4,000,000.00, taken from the quotation. Sending the same milestone again is refused with 409.

, recreate Keycloak so it imports the realm again. Local Keycloak keeps no data between containers:

```powershell
cd C:\dev\cbrms; docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d --force-recreate keycloak
```

To go back to Option A, run Option A's command. The `--build` flag rebuilds the console with its default settings.

### Ports

If 8085, 8086 or 8190 is taken, set `$env:CBRMS_PORT`, `$env:CBRMS_WEB_PORT` or `$env:CBRMS_SSO_PORT` before starting. If you change the SSO port, also change `oidc.url` in `frontend/config.sso.json`.

## Try the new Finance features in the console

All of these work in both options. Use two browser profiles (or one normal and one private window) so the maker and the approver are signed in at the same time.

1. **Cancel and replace.**
   - As `finance.maker`, open an issued, unpaid bill and choose **Cancel this bill**.
   - As `finance.approver`, approve it under **Approvals**.
   - On **GePG simulator**, choose **Answer waiting requests**. The bill shows as Cancelled.
   - LIMS can then send a replacement with `replacesBillId` set to the cancelled bill.
2. **New deadline.** Use **Give a new deadline** on an issued bill. The new date applies once the approver approves it and the simulator answers.
3. **Refund.**
   - Pay more than the total of a Microbiology (Partial) bill in the simulator. The excess becomes client credit.
   - On that bill, choose **Refund client credit**. Approve the request, then record the bank reference under **Refunds**.
4. **Reconciliation.**
   - Under **Reconciliation**, choose **Run reconciliation now** for today.
   - To see a difference, first add a line on the **GePG simulator** page. Then sign the day off.
5. **Client messages.** Every control number, payment, cancellation and new deadline queues a Kiswahili or English SMS or email. They are listed under **Client messages**. Locally, messages are recorded, not sent.
6. **Change a price.** As `finance.maker`, open **Catalogue**, pick a service and choose **New price**, starting today or later with the Finance authority reference. As `finance.approver`, approve it under **Approvals**. Bills already issued keep their price; platforms see the new version at `GET /internal/v1/catalogue`.
7. **Fix a misdirected payment.** In the simulator, pay an unknown control number: it lands under **Exceptions → Money not applied to any bill**. Choose **Move to a bill**, find the right bill and request the move; approve it as the other user. To undo a wrong allocation, use **Reverse** in a bill's Payments table. If the payer wants the money back instead, choose **Refund to payer**, approve it, then record the bank reference under **Refunds**; that closes the case.
8. **Audit trail.** Every approval, correction and export above is searchable under **Audit trail**.
9. **Check a bill (public).** Open http://localhost:8086/check and enter a control number.
   - The one-time code appears under **Client messages**, because local messages are recorded, not sent.
   - Enter the code to see the bill.

## Monitoring and alerts

CBRMS publishes its health at `/actuator/prometheus`. The figures include the oldest undelivered platform event, delays in control numbers, unapplied money, overdue cases, invariant violations, reconciliation lag, backup age, WAL archive lag and certificate expiry. The console's **Operations** page shows the same figures and alerts, using the NFR09 thresholds. Each alert names an owner and a runbook in `ops/RUNBOOKS.md`. A nightly check at 02:15 opens a case for every invariant violation.

To add Prometheus, Alertmanager and Grafana with Keycloak:

```powershell
cd C:\dev\cbrms; $env:CBRMS_PROM_CONFIG = "prometheus.sso.yml"; docker compose -f docker-compose.yml -f docker-compose.sso.yml -f docker-compose.ops.yml up -d --build
```

With the development users instead (no Keycloak):

```powershell
cd C:\dev\cbrms; Remove-Item Env:CBRMS_PROM_CONFIG -ErrorAction SilentlyContinue; docker compose -f docker-compose.yml -f docker-compose.ops.yml up -d --build
```

- Grafana: http://localhost:3100 (`admin` / `admin`) opens on the **CBRMS operations** dashboard.
- Prometheus: http://localhost:9190. Under **Status → Targets**, `cbrms` should be UP.
- Alertmanager: http://localhost:9193. Locally, alerts appear only there. For production, set up email receivers in `ops/alertmanager/alertmanager.yml`.

Backup and WAL archive alerts apply only where `CBRMS_BACKUP_EXPECTED` and `CBRMS_ARCHIVE_EXPECTED` are true (the `prod` profile turns both on). Backup jobs report each run by inserting a row into `ops_heartbeat`.

## Backups and the restore drill

The backup overlay replaces the database image with PostgreSQL 18 plus pgBackRest, and switches on WAL archiving, forced every 60 seconds for a 1-minute RPO. Backups go to a separate Docker volume, `cbrms-pgbackrest`. Add `-f docker-compose.backup.yml` to any start command:

```powershell
cd C:\dev\cbrms; $env:CBRMS_PROM_CONFIG = "prometheus.sso.yml"; docker compose -f docker-compose.yml -f docker-compose.sso.yml -f docker-compose.ops.yml -f docker-compose.backup.yml up -d --build
```

Then, once, create the backup repository and take the first full backup:

```powershell
docker exec -u postgres cbrms-postgres cbrms-backup init; docker exec -u postgres cbrms-postgres cbrms-backup full
```

Run a restore drill (AT11). It restores into a scratch copy, proves the newest data survived and measures the restore time:

```powershell
docker exec -u postgres cbrms-postgres cbrms-restore-drill
```

Schedule backups on this PC with Windows Task Scheduler: a full backup on Sundays at 01:00 and a differential every other night at 01:30. Run this in PowerShell as Administrator:

```powershell
$full = New-ScheduledTaskAction -Execute "docker.exe" -Argument "exec -u postgres cbrms-postgres cbrms-backup full"; $diff = New-ScheduledTaskAction -Execute "docker.exe" -Argument "exec -u postgres cbrms-postgres cbrms-backup diff"; Register-ScheduledTask -TaskName "CBRMS full backup" -Action $full -Trigger (New-ScheduledTaskTrigger -Weekly -DaysOfWeek Sunday -At 1am) -Force; Register-ScheduledTask -TaskName "CBRMS differential backup" -Action $diff -Trigger (New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday,Saturday -At 1:30am) -Force
```

Each run is recorded in `ops_heartbeat`, and the overlay turns on the backup and archive alerts. If no successful backup is recorded within 24 hours, the console's **Operations** page and Prometheus raise `BackupMissing`. On a production server, use cron or systemd timers on the database host, and follow `ops/postgres/pgbackrest.conf` for off-host, encrypted repositories. `ops/RUNBOOKS.md` (RB-05) covers failures and real recovery.

## Tests

Run the backend tests, including the integration tests against the `cbrms_test` database:

```powershell
cd C:\dev\cbrms\backend; docker run --rm -v "${PWD}:/src" -v cbrms-m2:/root/.m2 -w /src --network cbrms-net -e CBRMS_TEST_DB_URL=jdbc:postgresql://cbrms-postgres:5432/cbrms_test maven:3.9-eclipse-temurin-21 mvn -B verify
```

Stop the stack (add `-v` to also delete the database):

```powershell
cd C:\dev\cbrms; docker compose -f docker-compose.yml -f docker-compose.sso.yml down
```
