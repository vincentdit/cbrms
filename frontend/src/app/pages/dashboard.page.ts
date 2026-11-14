import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { OpsSnapshot, PlatformRow, Summary } from '../core/models';
import { MoneyPipe, stateInfo } from '../shared/format';
import { PlatformComponent } from '../shared/platform.component';

interface Task {
  count: number;
  title: string;
  detail: string;
  link: string;
  query?: Record<string, string>;
  tone: 'action' | 'alert';
}

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MoneyPipe, PlatformComponent],
  template: `
    <header class="page-head">
      <h1>Good {{ partOfDay }}, {{ firstName() }}</h1>
      <p class="lede">Bills, verified payments and what is still owed across LIMS, CIAP, TeLTP and ISICMS. Amounts are the figures CBRMS recorded.</p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (summary(); as s) {
      <section aria-labelledby="position-title">
        <h2 id="position-title">Position today</h2>
        <ul class="kpis">
          <li>
            <a class="kpi" routerLink="/reports">
              <span class="kpi-label">Received today</span>
              <span class="kpi-value">{{ s.collectedToday.amount | money }}</span>
              <span class="kpi-note">{{ s.collectedToday.payments }} verified {{ s.collectedToday.payments === 1 ? 'payment' : 'payments' }}, stored once</span>
            </a>
          </li>
          <li>
            <a class="kpi" data-tone="action" routerLink="/bills" [queryParams]="{ registration: 'Accepted', collection: 'Unpaid,PartiallyPaid' }">
              <span class="kpi-label">Still owed on issued bills</span>
              <span class="kpi-value">{{ s.outstanding.amount | money }}</span>
              <span class="kpi-note">{{ s.outstanding.bills }} {{ s.outstanding.bills === 1 ? 'bill' : 'bills' }} with a control number</span>
            </a>
          </li>
          <li>
            <a class="kpi" data-tone="ok" routerLink="/bills" [queryParams]="{ registration: 'Accepted', collection: 'Paid' }">
              <span class="kpi-label">Paid issued bills</span>
              <span class="kpi-value">{{ count('Paid') }}</span>
              <span class="kpi-note">Control number issued and fully paid</span>
            </a>
          </li>
          <li>
            <a class="kpi" routerLink="/bills" [queryParams]="{ registration: 'Accepted', collection: 'Unpaid' }">
              <span class="kpi-label">Unpaid issued bills</span>
              <span class="kpi-value">{{ count('Unpaid') }}</span>
              <span class="kpi-note">No verified payment applied yet</span>
            </a>
          </li>
          <li>
            <a class="kpi" data-tone="action" routerLink="/bills" [queryParams]="{ registration: 'Accepted', collection: 'PartiallyPaid' }">
              <span class="kpi-label">Partly paid</span>
              <span class="kpi-value">{{ count('PartiallyPaid') }}</span>
              <span class="kpi-note">A balance is still outstanding</span>
            </a>
          </li>
          <li>
            <a class="kpi" [attr.data-tone]="s.reconciliationToReview > 0 ? 'action' : 'ok'" routerLink="/reconciliation">
              <span class="kpi-label">Reconciliation to sign off</span>
              <span class="kpi-value">{{ s.reconciliationToReview }}</span>
              <span class="kpi-note">GePG and CBRMS compared; awaiting a Finance officer</span>
            </a>
          </li>
        </ul>
      </section>

      <section aria-labelledby="needs-title" class="block">
        <h2 id="needs-title">Needs your attention</h2>
        @if (tasks().length === 0) {
          <p class="all-clear">Nothing is waiting. Approvals, reconciliation, refunds, exceptions and GePG submissions are all up to date.</p>
        } @else {
          <ul class="tasks">
            @for (t of tasks(); track t.title) {
              <li [attr.data-tone]="t.tone">
                <a [routerLink]="t.link" [queryParams]="t.query">
                  <span class="task-count">{{ t.count }}</span>
                  <span class="task-text"><strong>{{ t.title }}</strong><span>{{ t.detail }}</span></span>
                </a>
              </li>
            }
          </ul>
        }
      </section>

      <section aria-labelledby="money-title" class="block ledger">
        <h2 id="money-title">Issued bills by payment status</h2>
        <p class="lede money-note">Counts of bills GePG has accepted. Paid means the recorded payments cover the bill. Unpaid is not revenue.</p>

        @if (collectionTotal() > 0) {
          <div class="split" role="img" [attr.aria-label]="splitLabel()">
            @for (part of split(); track part.state) {
              <span [attr.data-tone]="part.tone" [style.flex-grow]="part.count"></span>
            }
          </div>
          <ul class="split-legend">
            @for (part of split(); track part.state) {
              <li [attr.data-tone]="part.tone">
                <a routerLink="/bills" [queryParams]="{ collection: part.state, registration: 'Accepted' }">{{ part.label }}: {{ part.count }}</a>
              </li>
            }
          </ul>
        }
      </section>

      @if (platforms().length > 0) {
        <section aria-labelledby="platforms-title" class="block">
          <h2 id="platforms-title">Bills by originating platform</h2>
          <p class="lede money-note">Every bill stays tied to the platform that requested it. These are bill counts, not revenue. Revenue by platform is under Reports.</p>
          <ul class="platform-board">
            @for (pl of platforms(); track pl.code) {
              <li>
                <a routerLink="/bills" [queryParams]="{ source: pl.code }">
                  <app-platform [code]="pl.code" />
                  <strong>{{ pl.name }}</strong>
                  <span>{{ pl.bills }} {{ pl.bills === 1 ? 'bill recorded' : 'bills recorded' }}</span>
                </a>
              </li>
            }
          </ul>
        </section>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  protected readonly summary = signal<Summary | null>(null);
  protected readonly platforms = signal<PlatformRow[]>([]);
  private readonly ops = signal<OpsSnapshot | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly partOfDay = (() => {
    const h = Number(new Intl.DateTimeFormat('en-GB', { hour: 'numeric', hourCycle: 'h23', timeZone: 'Africa/Dar_es_Salaam' }).format(new Date()));
    return h < 12 ? 'morning' : h < 17 ? 'afternoon' : 'evening';
  })();

  protected readonly firstName = computed(() => (this.auth.me()?.displayName ?? '').split(/[\s(]/)[0]);

  protected readonly tasks = computed<Task[]>(() => {
    const s = this.summary();
    if (!s) {
      return [];
    }
    const list: Task[] = [
      { count: s.pendingApprovals, title: 'Waiting for approval', detail: 'Bills, cancellations, new deadlines and refunds', link: '/approvals', tone: 'action' },
      { count: s.reconciliationToReview, title: 'Reconciliation to sign off', detail: 'GePG and CBRMS compared; a Finance officer must sign off', link: '/reconciliation', tone: 'action' },
      { count: s.refundsToExecute, title: 'Refunds to pay out', detail: 'Approved refunds waiting for the bank reference', link: '/refunds', tone: 'action' },
      { count: s.openCases, title: 'Open exceptions', detail: 'Unmatched or mismatched payments and delays', link: '/cases', tone: 'alert' },
      { count: s.ackUnknown, title: 'GePG outcome unknown', detail: 'Resolve by enquiry before any resubmission', link: '/bills', query: { registration: 'AckUnknown' }, tone: 'alert' },
      { count: s.failedDeliveries, title: 'Platform updates not delivered', detail: 'A platform has not received a payment update', link: '/cases', tone: 'alert' },
      { count: s.awaitingControlNumber, title: 'Waiting for control numbers', detail: 'Sent to GePG, no control number yet', link: '/bills', query: { registration: 'Submitted,AwaitingControlNumber' }, tone: 'action' },
    ];
    const serious = (this.ops()?.alerts ?? []).filter((a) => a.severity !== 'info').length;
    list.unshift({ count: serious, title: 'Operational alerts', detail: 'Delays, recovery or certificates need ICTT attention', link: '/operations', tone: 'alert' });
    return list.filter((t) => t.count > 0);
  });

  protected readonly split = computed(() => {
    const by = this.summary()?.byCollection ?? {};
    return ['Unpaid', 'PartiallyPaid', 'Paid', 'Overpaid']
      .map((state) => ({ state, count: by[state] ?? 0, ...stateInfo(state) }))
      .filter((p) => p.count > 0);
  });
  protected readonly collectionTotal = computed(() => this.split().reduce((n, p) => n + p.count, 0));
  protected readonly splitLabel = computed(() => this.split().map((p) => `${p.label} ${p.count}`).join(', '));

  protected count(state: string): number {
    return this.summary()?.byCollection?.[state] ?? 0;
  }

  constructor() {
    this.api.summary().subscribe({ next: (s) => this.summary.set(s), error: (e) => this.error.set(describeError(e)) });
    this.api.operations().subscribe({ next: (o) => this.ops.set(o), error: () => this.ops.set(null) });
    this.api.platforms().subscribe({ next: (list) => this.platforms.set(list), error: () => this.platforms.set([]) });
  }
}
