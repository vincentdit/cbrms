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

## Start everything (PowerShell)

### Option A: development users (no Keycloak)

```powershell
cd C:\dev\cbrms; docker compose up -d --build
```

- Console: http://localhost:8086. Sign in as `finance.approver` / `approver-dev` (can approve) or `finance.maker` / `maker-dev`.
- Backend API: http://localhost:8085. The LIMS development client is `lims` / `lims-dev-secret` (HTTP Basic).

### Option B: Keycloak single sign-on

```powershell
cd C:\dev\cbrms; docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d --build
```

- Console: http://localhost:8086, then **Sign in with TIRDO account**. The users are the same (`finance.maker` / `maker-dev`, `finance.approver` / `approver-dev`), but now Keycloak checks them.
- Keycloak admin: http://localhost:8190, user `admin` / `admin`, realm `tirdo`. The first start takes about a minute.
- The backend now accepts only Keycloak tokens. Basic sign-in is refused.
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
6. **Check a bill (public).** Open http://localhost:8086/check and enter a control number.
   - The one-time code appears under **Client messages**, because local messages are recorded, not sent.
   - Enter the code to see the bill.

## Tests

Run the backend tests, including the integration tests against the `cbrms_test` database:

```powershell
cd C:\dev\cbrms\backend; docker run --rm -v "${PWD}:/src" -v cbrms-m2:/root/.m2 -w /src --network cbrms-net -e CBRMS_TEST_DB_URL=jdbc:postgresql://cbrms-postgres:5432/cbrms_test maven:3.9-eclipse-temurin-21 mvn -B verify
```

Stop the stack (add `-v` to also delete the database):

```powershell
cd C:\dev\cbrms; docker compose -f docker-compose.yml -f docker-compose.sso.yml down
```
