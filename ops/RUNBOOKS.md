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
**Alerts:** `BackupMissing` (no successful backup in 24 hours), `WalArchiveBehind` (over 5 minutes) and `WalArchiveFailing`. **Owner:** ICTT infrastructure.

1. Check the backup job and repository space. The job records each run in `ops_heartbeat`, as `backup.full` or `backup.diff`, with `ok` true or false.
2. For archive failures, read the PostgreSQL log and `SELECT * FROM pg_stat_archiver;`.
3. Every acknowledged GePG payment must survive a restore (RPO 1 minute). If archiving was down, record the exposure window, then reconcile that window against GePG once it recovers.

## RB-06 Certificates (SOP-14)
**Alerts:** `CertificateExpiresIn60Days`, `CertificateExpiresIn30Days` (warnings) and `CertificateExpiresIn7Days` (critical). **Owner:** ICTT integration custodian.

1. Start renewal at 60 days. Test the new certificate in the GePG test environment before cutover.
2. Update `cbrms.ops.certificates` with the new path or expiry date, so the alert clears.

## RB-07 Client notices
**Alert:** `ClientNoticesFailed`. **Owner:** ICTT and customer service.

1. Under **Client messages**, find the failed messages and the provider's error.
2. Notices never affect financial records. Tell clients by phone if a control number or receipt didn't reach them.
