import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { OpsSnapshot, Summary } from '../core/models';
import { MoneyPipe, stateInfo } from '../shared/format';

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
  imports: [RouterLink, MoneyPipe],
  template: `
    <header class="page-head">
      <h1>Good {{ partOfDay }}, {{ firstName() }}</h1>
      <p class="lede">What needs Finance today, and where collections stand.</p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (summary(); as s) {
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
        <h2 id="money-title">Collections</h2>
        <dl class="ledger-figures">
          <div>
            <dt>Received today</dt>
            <dd>{{ s.collectedToday.amount | money }}</dd>
            <dd class="sub">{{ s.collectedToday.payments }} {{ s.collectedToday.payments === 1 ? 'payment' : 'payments' }}</dd>
          </div>
          <div>
            <dt>Still owed on issued bills</dt>
            <dd>{{ s.outstanding.amount | money }}</dd>
            <dd class="sub">{{ s.outstanding.bills }} {{ s.outstanding.bills === 1 ? 'bill' : 'bills' }}</dd>
          </div>
        </dl>

        @if (collectionTotal() > 0) {
          <h3>Issued bills by payment status</h3>
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
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  protected readonly summary = signal<Summary | null>(null);
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

  constructor() {
    this.api.summary().subscribe({ next: (s) => this.summary.set(s), error: (e) => this.error.set(describeError(e)) });
    this.api.operations().subscribe({ next: (o) => this.ops.set(o), error: () => this.ops.set(null) });
  }
}
