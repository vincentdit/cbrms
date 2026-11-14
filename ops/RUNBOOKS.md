# CBRMS operational runbooks

Each alert (Prometheus, Alertmanager and the console's **Operations** page) names one of these runbooks. Thresholds come from NFR09 in document 04. SOP numbers refer to document 05.

Every step that moves money goes through the console, under maker-checker. Nobody edits financial tables directly.

## RB-00 Service down
**Alert:** `CbrmsDown`, when Prometheus can't read CBRMS for 2 minutes. **Owner:** ICTT infrastructure.

1. Check the containers: `docker ps --filter name=cbrms`. Then check the backend log: `docker logs --tail 200 cbrms-app`.
2. If the database is down, follow RB-05 before restarting anything.
3. GePG retries its callbacks, and none is acknowledged while CBRMS is down, so no payment is lost. After recovery, watch `PlatformEventsDelayed` clear.
4. Record the incident with its timeline (document 08, service management).

## RB-01 Invariant violation
**Alert:** `InvariantViolation`, plus an `INVARIANT_VIOLATION` case from the nightly check at 02:15. **Owners:** Director, ICTT and Head of Finance.

1. Query `SELECT * FROM v_invariant_violations;` (read-only).
2. Stop releasing services for the affected bills until the violation is explained.
3. Find the cause. Typical causes are a direct database edit, a failed migration or a defect. Fix it through a correction (approved under maker-checker), never by editing ledger rows.
4. Close the case with the evidence. Raise a defect if code caused it.

## RB-02 Event delivery delayed
**Alerts:** `PlatformEventsDelayed` (oldest undelivered event over 5 minutes) and `PlatformEventsFailed`. **Owner:** ICTT integration custodian.

1. Under **Exceptions**, look for `DELIVERY_FAILED` cases. They name the platform.
2. Check that the platform's endpoint is up and accepts the HMAC signature (FR36).
3. When the platform is back, it recovers missed events from `GET /internal/v1/events?after=<last position>`. CBRMS keeps retrying with backoff in any case.
4. Close the cases once the platform confirms it is up to date.

## RB-03 GePG delays (SOP-02)
**Alerts:** `ControlNumbersDelayed` (waiting over 15 minutes) and `GepgOutcomeUnknown` (AckUnknown for 30 minutes). **Owner:** ICTT integration custodian.

1. Never resubmit an AckUnknown bill. Resolve it by GePG enquiry, under SOP-02.
2. Check GePG's service status and the certificate (RB-06).
3. Tell Finance and the platform owner if clients are waiting for control numbers.

## RB-04 Reconciliation (SOP-08)
**Alerts:** `ReconciliationBehind` (after 10:00, a business day has no GePG run) and `ExceptionsOverdue`. **Owner:** Head of Finance.

1. Under **Reconciliation**, run the missing day.
2. If the GePG feed is missing, CBRMS records missing evidence, not a zero day. Escalate to GePG.
3. Work every difference under **Exceptions**, then sign the day off.
4. Public holidays count as business days to CBRMS. Sign off a holiday with a note.

## RB-05 Backup and archive
**Alerts:** `BackupMissing` (no successful backup in 24 hours, or the last backup failed), `WalArchiveBehind` (over 5 minutes) and `WalArchiveFailing`. **Owner:** ICTT infrastructure.

1. See what the repository holds: `docker exec -u postgres cbrms-postgres cbrms-backup info`.
2. Read the last backup result, newest first: `SELECT name, ok, detail, occurred_at FROM ops_heartbeat WHERE name LIKE 'backup.%' ORDER BY occurred_at DESC LIMIT 5;`
3. Run a backup by hand: `docker exec -u postgres cbrms-postgres cbrms-backup diff` (or `full`). It records its own heartbeat.
4. For archive failures, read the PostgreSQL log (`docker logs cbrms-postgres`) and `SELECT * FROM pg_stat_archiver;`. Then check the archive end to end with `docker exec -u postgres cbrms-postgres pgbackrest --stanza=cbrms check`.
5. Every acknowledged GePG payment must survive a restore (RPO 1 minute). If archiving was down, record the exposure window, then reconcile that window against GePG once it recovers.

### Restore drill (AT11)
Run the drill before go-live and then at least once a year (document 08). Monthly is recommended while the system is new.

`docker exec -u postgres cbrms-postgres cbrms-restore-drill`

The drill:
1. writes a marker into the live database and waits until its WAL is archived;
2. restores the latest backup plus every archived WAL file into a scratch directory, with archiving off;
3. starts the copy on a private socket, then checks that the marker, payments, inbox and outbox records came back and that no invariant is broken;
4. records the measured restore time against the 2-hour RTO, as `restore.drill` in `ops_heartbeat`.

It never touches the live data files. A failed drill exits with an error and records why.

### Real recovery
To recover from losing the primary database:
1. Stop CBRMS: `docker stop cbrms-app`.
2. Restore with `pgbackrest --stanza=cbrms --delta restore` into the data directory of a stopped PostgreSQL.
3. Start PostgreSQL and let it replay WAL. Check `v_invariant_violations`.
4. Reconcile recovered payments against GePG before automated service release resumes (document 06).
5. Start CBRMS again.

Record the timeline as an incident.

## RB-06 Certificates (SOP-14)
**Alerts:** `CertificateExpiresIn60Days`, `CertificateExpiresIn30Days` (warnings) and `CertificateExpiresIn7Days` (critical). **Owner:** ICTT integration custodian.

1. Start renewal at 60 days. Test the new certificate in the GePG test environment before cutover.
2. Update `cbrms.ops.certificates` with the new path or expiry date, so the alert clears.

## RB-07 Client notices
**Alert:** `ClientNoticesFailed`. **Owner:** ICTT and customer service.

1. Under **Client messages**, find the failed messages and the provider's error.
2. Notices never affect financial records. Tell clients by phone if a control number or receipt didn't reach them.

## RB-08 Staff sign-in and MFA
**Trigger:** a staff member lost their phone, changed phones or is locked out. **Owner:** ICTT (Keycloak administrator).

- Finance officers, approvers, auditors and Finance viewers must sign in with password plus an authenticator app (Microsoft Authenticator, Google Authenticator, FreeOTP). Keycloak asks them to set it up at their first sign-in.
- The backend checks this too (`cbrms.security.staff-acr: mfa`): a staff token from a password-only sign-in gets no staff rights, and the backend log says `Staff role ... ignored`.
- **Lost or replaced phone:** Keycloak admin, realm `tirdo`, **Users**, the user, **Credentials**, delete the `otp` credential. Confirm who is asking by phone or in person first. At the next sign-in they set up the new phone.
- **Locked out:** five wrong passwords lock the account for a few minutes, growing to 15 minutes. **Users**, the user, turn off **Temporarily locked** (or wait). Repeated lockouts may be an attack: check **Events** (`LOGIN_ERROR`) for the source address.
- **Production password policy:** Keycloak admin, **Authentication**, **Policies**, set minimum length to 12. The development realm uses 8 only because the development passwords are short.
- Sign-ins, failures and authenticator changes are kept as Keycloak events for 90 days; admin changes as admin events.

## RB-09 Client secret rotation (AT17)
**When:** at least yearly, when someone who knew a secret leaves, or at once if a secret may have leaked. **Owner:** ICTT, with the platform team.

1. Agree a time with the platform team. Their calls fail from the rotation until they load the new secret.
2. Rotate (PowerShell, from the repository folder):
   `.\sso\Rotate-ClientSecret.ps1 -Client lims -KeycloakUrl https://<keycloak> -OutFile <secure path>`
   Use `-WhatIf` first to check access without changing anything. Clients: `lims`, `ciap`, `teltp`, `isicms`, `monitoring`.
3. The script proves the new secret works and the token is for `cbrms`. Hand the secret over securely, then delete the file.
4. Check the platform's next calls succeed (console **Operations**, or the platform's own logs). For `monitoring`, reload Prometheus and check the `cbrms` target is up.
5. Keycloak records the rotation as an admin event (who and when; the secret is not recorded).
