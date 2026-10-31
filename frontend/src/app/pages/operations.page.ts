import { Component, computed, inject, OnDestroy, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { OpsSnapshot } from '../core/models';
import { EatPipe, MoneyPipe } from '../shared/format';

type Tone = 'ok' | 'action' | 'alert' | 'quiet';

interface Tile {
  label: string;
  value: string;
  note: string;
  tone: Tone;
  link?: string;
}

/** FR27e: is CBRMS healthy right now? Same thresholds as the Prometheus alerts (NFR09). */
@Component({
  selector: 'app-operations',
  imports: [RouterLink, EatPipe],
  template: `
    <header class="page-head">
      <h1>Operations</h1>
      <p class="lede">
        Whether payments, control numbers and platform updates are flowing, and whether CBRMS could be recovered today.
        Figures refresh every 30 seconds; the same checks raise alerts to ICTT and Finance.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (snap(); as s) {
      <div class="filters">
        <p class="sub ops-time">Read {{ s.takenAt | eat }}</p>
        <button type="button" [disabled]="busy()" (click)="refresh()">Refresh now</button>
        @if (canAct()) {
          <button type="button" [disabled]="busy()" (click)="check()">Run invariant check now</button>
        }
      </div>

      <section class="block" aria-labelledby="alerts-title">
        <h2 id="alerts-title">Alerts</h2>
        @if (s.alerts.length === 0) {
          <p class="all-clear">No alerts. Everything is within its limits.</p>
        } @else {
          <ul class="ops-alerts">
            @for (a of s.alerts; track a.title) {
              <li [attr.data-severity]="a.severity">
                <span class="ops-sev">{{ a.severity }}</span>
                <div>
                  <strong>{{ a.title }}</strong>
                  <span class="sub">{{ a.detail }}</span>
                  <span class="sub">Owner: {{ a.owner }} · Runbook {{ a.runbook }}</span>
                </div>
              </li>
            }
          </ul>
        }
      </section>

      <section class="block" aria-labelledby="flow-title">
        <h2 id="flow-title">Flow and workload</h2>
        <ul class="ops-tiles">
          @for (t of tiles(); track t.label) {
            <li [attr.data-tone]="t.tone">
              <span class="ops-label">{{ t.label }}</span>
              @if (t.link) {
                <a [routerLink]="t.link" class="ops-value">{{ t.value }}</a>
              } @else {
                <span class="ops-value">{{ t.value }}</span>
              }
              <span class="sub">{{ t.note }}</span>
            </li>
          }
        </ul>
      </section>

      <section class="block" aria-labelledby="rec-title">
        <h2 id="rec-title">Recovery</h2>
        <ul class="ops-tiles">
          <li [attr.data-tone]="backupTone()">
            <span class="ops-label">Last good backup</span>
            <span class="ops-value">{{ s.backupAgeSeconds === null ? 'None recorded' : age(s.backupAgeSeconds) + ' ago' }}</span>
            <span class="sub">{{ s.backupExpected ? 'Required here: alert after 24 hours' : 'Not required in this environment' }}</span>
          </li>
          <li [attr.data-tone]="archiveTone()">
            <span class="ops-label">Last WAL file archived</span>
            <span class="ops-value">{{ s.walArchiveAgeSeconds === null ? 'Never' : age(s.walArchiveAgeSeconds) + ' ago' }}</span>
            <span class="sub">
              {{ s.archiveExpected ? 'Required here: alert after 5 minutes (RPO 1 minute)' : 'Not required in this environment' }}
              @if (s.walArchiveFailures > 0) { · {{ s.walArchiveFailures }} failure(s) }
            </span>
          </li>
        </ul>
      </section>

      <section class="block" aria-labelledby="cert-title">
        <h2 id="cert-title">Certificates</h2>
        @if (s.certificates.length === 0) {
          <p class="empty">No certificates registered. Add them to cbrms.ops.certificates.</p>
        } @else {
          <ul class="ops-tiles">
            @for (c of s.certificates; track c.name) {
              <li [attr.data-tone]="certTone(c.daysLeft, c.error)">
                <span class="ops-label">{{ c.name }}</span>
                <span class="ops-value">{{ c.error ? 'Unreadable' : c.daysLeft + ' days left' }}</span>
                <span class="sub">{{ c.error ?? 'Expires ' + (c.notAfter | eat: false) + '; alerts at 60, 30 and 7 days' }}</span>
              </li>
            }
          </ul>
        }
      </section>
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class OperationsPage implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  protected readonly snap = signal<OpsSnapshot | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly canAct = computed(() => this.auth.isOfficer() || this.auth.canApprove());
  private readonly timer = setInterval(() => this.load(), 30_000);
  private readonly money = new MoneyPipe();

  protected readonly tiles = computed<Tile[]>(() => {
    const s = this.snap();
    if (!s) {
      return [];
    }
    return [
      { label: 'Invariant violations', value: String(s.invariantViolations), note: 'Must be 0', tone: s.invariantViolations > 0 ? 'alert' : 'ok', link: s.invariantViolations > 0 ? '/cases' : undefined },
      { label: 'Oldest undelivered platform event', value: s.oldestPendingEventSeconds ? this.age(s.oldestPendingEventSeconds) : 'None', note: 'Limit 5 minutes', tone: s.oldestPendingEventSeconds > 300 ? 'alert' : s.oldestPendingEventSeconds > 60 ? 'action' : 'ok' },
      { label: 'Failed platform deliveries', value: String(s.failedDeliveries), note: 'Gave up after retries', tone: s.failedDeliveries > 0 ? 'alert' : 'ok', link: s.failedDeliveries > 0 ? '/cases' : undefined },
      { label: 'Oldest bill waiting for a control number', value: s.oldestAwaitingControlNumberSeconds ? this.age(s.oldestAwaitingControlNumberSeconds) : 'None', note: 'Limit 15 minutes', tone: s.oldestAwaitingControlNumberSeconds > 900 ? 'alert' : s.oldestAwaitingControlNumberSeconds > 300 ? 'action' : 'ok' },
      { label: 'GePG outcome unknown', value: String(s.ackUnknown), note: 'Resolve by enquiry', tone: s.ackUnknown > 0 ? 'action' : 'ok', link: s.ackUnknown > 0 ? '/bills' : undefined },
      { label: 'Reconciliation', value: s.reconciliationDaysBehind === 0 ? 'Up to date' : s.reconciliationDaysBehind + ' day(s) behind', note: s.lastReconciledDay ? 'Last run for ' + s.lastReconciledDay : 'No run yet', tone: s.reconciliationDaysBehind > 0 ? 'action' : 'ok', link: '/reconciliation' },
      { label: 'Money not applied to a bill', value: this.money.transform(s.unallocatedAmount), note: s.unallocatedPayments + ' payment(s)', tone: s.unallocatedPayments > 0 ? 'action' : 'ok', link: '/cases' },
      { label: 'Open exceptions', value: String(s.openCases), note: s.overdueCases + ' overdue', tone: s.overdueCases > 0 ? 'action' : 'quiet', link: '/cases' },
      { label: 'Failed client messages', value: String(s.failedNotices), note: 'Financial records unaffected', tone: s.failedNotices > 0 ? 'action' : 'ok', link: '/notices' },
    ];
  });

  constructor() {
    this.load();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected age(seconds: number): string {
    if (seconds < 120) {
      return `${seconds} s`;
    }
    if (seconds < 7200) {
      return `${Math.round(seconds / 60)} min`;
    }
    if (seconds < 172800) {
      return `${Math.round(seconds / 3600)} h`;
    }
    return `${Math.round(seconds / 86400)} days`;
  }

  protected backupTone(): Tone {
    const s = this.snap()!;
    if (!s.backupExpected) {
      return 'quiet';
    }
    return s.backupAgeSeconds === null || s.backupAgeSeconds > 86400 || s.lastBackupOk === false ? 'alert' : 'ok';
  }

  protected archiveTone(): Tone {
    const s = this.snap()!;
    if (!s.archiveExpected) {
      return 'quiet';
    }
    return s.walArchiveAgeSeconds === null || s.walArchiveAgeSeconds > 300 ? 'alert' : 'ok';
  }

  protected certTone(days: number | null, error: string | null): Tone {
    if (error || days === null) {
      return 'alert';
    }
    return days <= 7 ? 'alert' : days <= 60 ? 'action' : 'ok';
  }

  protected refresh(): void {
    this.busy.set(true);
    this.api.refreshOperations().subscribe({
      next: (s) => {
        this.busy.set(false);
        this.snap.set(s);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  protected check(): void {
    this.busy.set(true);
    this.api.runInvariantCheck().subscribe({
      next: (r) => {
        this.busy.set(false);
        this.done.set(r.violations === 0 ? 'Invariant check passed: no violations.'
          : `${r.violations} violation(s) found; ${r.casesOpened} new case(s) opened under Exceptions.`);
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  private load(): void {
    this.api.operations().subscribe({ next: (s) => this.snap.set(s), error: (e) => this.error.set(describeError(e)) });
  }
}
