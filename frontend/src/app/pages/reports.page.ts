import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { CollectionsReport, GroupBy, OutstandingReport } from '../core/models';
import { ControlNumberPipe, EatPipe, MoneyPipe } from '../shared/format';

const TODAY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());
const MONTH_START = TODAY.slice(0, 8) + '01';

const GROUP_LABEL: Record<GroupBy, string> = { platform: 'Platform', service: 'Service', gfs: 'GFS code' };

@Component({
  selector: 'app-reports',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, ControlNumberPipe],
  template: `
    <header class="page-head">
      <h1>Reports</h1>
      <p class="lede">Money received through GePG, and what clients still owe. Every figure is calculated by CBRMS from its payment records.</p>
    </header>

    <div class="chips" role="tablist" aria-label="Report">
      <button type="button" role="tab" class="chip" [class.on]="view() === 'collections'" [attr.aria-selected]="view() === 'collections'" (click)="show('collections')">
        Collections
      </button>
      <button type="button" role="tab" class="chip" [class.on]="view() === 'outstanding'" [attr.aria-selected]="view() === 'outstanding'" (click)="show('outstanding')">
        Outstanding balances
      </button>
    </div>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (view() === 'collections') {
      <form class="filters" (ngSubmit)="loadCollections()">
        <div class="field">
          <label for="from">From</label>
          <input id="from" name="from" type="date" [max]="to" [(ngModel)]="from" required />
        </div>
        <div class="field">
          <label for="to">To</label>
          <input id="to" name="to" type="date" [min]="from" [max]="today" [(ngModel)]="to" required />
        </div>
        <div class="field">
          <label for="group">Group by</label>
          <select id="group" name="group" [(ngModel)]="groupBy">
            <option value="platform">Platform</option>
            <option value="service">Service</option>
            <option value="gfs">GFS code</option>
          </select>
        </div>
        <button type="submit" class="primary" [disabled]="busy()">Show</button>
        <button type="button" [disabled]="busy() || !collections()" (click)="downloadCollections()">Download CSV</button>
      </form>

      @if (collections(); as c) {
        <section class="block ledger" aria-labelledby="col-title">
          <h2 id="col-title">{{ c.from | eat: false }} to {{ c.to | eat: false }}</h2>
          <dl class="ledger-figures report-figures">
            <div>
              <dt>Received from GePG</dt>
              <dd>{{ c.received | money }}</dd>
              <dd class="sub">{{ c.payments }} {{ c.payments === 1 ? 'payment' : 'payments' }}</dd>
            </div>
            <div>
              <dt>Applied to bills</dt>
              <dd>{{ c.applied | money }}</dd>
              <dd class="sub">Counted as revenue</dd>
            </div>
            <div>
              <dt>Held as client credit</dt>
              <dd>{{ c.credit | money }}</dd>
              <dd class="sub">Overpayments, not revenue</dd>
            </div>
            <div>
              <dt>In suspense</dt>
              <dd [class.alert-figure]="!isZero(c.suspense)">{{ c.suspense | money }}</dd>
              <dd class="sub">No matching bill yet</dd>
            </div>
          </dl>

          @if (c.rows.length === 0) {
            <p class="empty">No payments were applied to bills in this period.</p>
          } @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="visually-hidden">Amount applied to bills by {{ groupLabel(c.groupBy) }}</caption>
                <thead>
                  <tr>
                    <th scope="col">{{ groupLabel(c.groupBy) }}</th>
                    <th scope="col" class="num">Payments</th>
                    <th scope="col" class="num">Applied to bills</th>
                    <th scope="col" class="share-col"><span class="visually-hidden">Share</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of c.rows; track r.key) {
                    <tr>
                      <td>
                        <strong class="cn">{{ c.groupBy === 'platform' ? r.key.toUpperCase() : r.key }}</strong>
                        <span class="sub">{{ r.name }}</span>
                      </td>
                      <td class="num">{{ r.payments }}</td>
                      <td class="num">{{ r.amount | money }}</td>
                      <td class="share-col" aria-hidden="true"><span class="share-bar" [style.width.%]="share(r.amount, c)"></span></td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row">Total applied</th>
                    <td></td>
                    <td class="num strong">{{ c.applied | money }}</td>
                    <td></td>
                  </tr>
                </tfoot>
              </table>
            </div>
            @if (c.apportioned) {
              <p class="hint">A payment on a bill with several lines is shared across the lines in proportion to their totals.</p>
            }
          }
        </section>
      } @else if (busy()) {
        <p class="loading">Loading…</p>
      }
    }

    @if (view() === 'outstanding') {
      <div class="filters">
        <button type="button" [disabled]="busy()" (click)="loadOutstanding()">Refresh</button>
        <button type="button" [disabled]="busy() || !outstanding()" (click)="downloadOutstanding()">Download CSV (every bill)</button>
      </div>

      @if (outstanding(); as o) {
        <section class="block" aria-labelledby="age-title">
          <h2 id="age-title">Owed on issued bills, as of {{ o.asOf | eat: false }}</h2>
          @if (o.rows.length === 0) {
            <p class="all-clear">Nothing is owed on issued bills.</p>
          } @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="visually-hidden">Outstanding balances by platform and days past due</caption>
                <thead>
                  <tr>
                    <th scope="col">Platform</th>
                    <th scope="col" class="num">Bills</th>
                    <th scope="col" class="num">Not yet due</th>
                    <th scope="col" class="num">1–30 days</th>
                    <th scope="col" class="num">31–60 days</th>
                    <th scope="col" class="num">61–90 days</th>
                    <th scope="col" class="num">Over 90 days</th>
                    <th scope="col" class="num">Total</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of o.rows; track r.platform) {
                    <tr>
                      <th scope="row"><a routerLink="/bills" [queryParams]="{ source: r.platform, registration: 'Accepted', collection: 'Unpaid,PartiallyPaid' }">{{ r.platform.toUpperCase() }}</a></th>
                      <td class="num">{{ r.bills }}</td>
                      <td class="num" [class.muted]="isZero(r.notYetDue)">{{ r.notYetDue | money }}</td>
                      <td class="num" [class.muted]="isZero(r.days1to30)">{{ r.days1to30 | money }}</td>
                      <td class="num" [class.muted]="isZero(r.days31to60)">{{ r.days31to60 | money }}</td>
                      <td class="num" [class.muted]="isZero(r.days61to90)">{{ r.days61to90 | money }}</td>
                      <td class="num" [class.muted]="isZero(r.over90)" [class.alert-figure]="!isZero(r.over90)">{{ r.over90 | money }}</td>
                      <td class="num strong">{{ r.total | money }}</td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row">All platforms</th>
                    <td class="num">{{ o.totals.bills }}</td>
                    <td class="num">{{ o.totals.notYetDue | money }}</td>
                    <td class="num">{{ o.totals.days1to30 | money }}</td>
                    <td class="num">{{ o.totals.days31to60 | money }}</td>
                    <td class="num">{{ o.totals.days61to90 | money }}</td>
                    <td class="num">{{ o.totals.over90 | money }}</td>
                    <td class="num strong">{{ o.totals.total | money }}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          }
        </section>

        @if (overdue().length > 0) {
          <section class="block" aria-labelledby="overdue-title">
            <h2 id="overdue-title">Overdue bills, longest first</h2>
            <div class="table-wrap">
              <table class="data">
                <thead>
                  <tr>
                    <th scope="col" class="col-client">Client and reference</th>
                    <th scope="col">Control number</th>
                    <th scope="col">Was due</th>
                    <th scope="col" class="num">Days overdue</th>
                    <th scope="col" class="num">Balance</th>
                  </tr>
                </thead>
                <tbody>
                  @for (b of overdue(); track b.billId) {
                    <tr>
                      <td>
                        <a [routerLink]="['/bills', b.billId]" class="row-link">{{ b.payerName }}</a>
                        <span class="sub">{{ b.platform.toUpperCase() }} {{ b.sourceReference }}</span>
                      </td>
                      <td class="cn">{{ b.controlNumber | controlNumber }}</td>
                      <td class="nowrap">{{ b.dueDate | eat: false }}</td>
                      <td class="num" [class.alert-figure]="b.daysOverdue > 90">{{ b.daysOverdue }}</td>
                      <td class="num strong">{{ b.balance | money }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (overdueCount() > overdue().length) {
              <p class="hint">Showing the {{ overdue().length }} longest overdue of {{ overdueCount() }}. The CSV lists every bill.</p>
            }
          </section>
        }
      } @else if (busy()) {
        <p class="loading">Loading…</p>
      }
    }
  `,
})
export class ReportsPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly tab = input<string>('');

  protected readonly today = TODAY;
  protected from = MONTH_START;
  protected to = TODAY;
  protected groupBy: GroupBy = 'platform';
  protected readonly view = signal<'collections' | 'outstanding'>('collections');
  protected readonly collections = signal<CollectionsReport | null>(null);
  protected readonly outstanding = signal<OutstandingReport | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly overdueAll = computed(() =>
    (this.outstanding()?.bills ?? []).filter((b) => b.daysOverdue > 0).sort((a, b) => b.daysOverdue - a.daysOverdue),
  );
  protected readonly overdue = computed(() => this.overdueAll().slice(0, 50));
  protected readonly overdueCount = computed(() => this.overdueAll().length);

  constructor() {
    queueMicrotask(() => this.show(this.tab() === 'outstanding' ? 'outstanding' : 'collections'));
  }

  protected show(view: 'collections' | 'outstanding'): void {
    this.view.set(view);
    this.error.set(null);
    void this.router.navigate([], { queryParams: { tab: view === 'outstanding' ? 'outstanding' : null }, replaceUrl: true });
    if (view === 'collections' && !this.collections()) {
      this.loadCollections();
    }
    if (view === 'outstanding' && !this.outstanding()) {
      this.loadOutstanding();
    }
  }

  protected groupLabel(g: GroupBy): string {
    return GROUP_LABEL[g];
  }

  protected isZero(v: string): boolean {
    return Number(v) === 0;
  }

  /** Bar length only; the amounts shown are the server's. */
  protected share(amount: string, c: CollectionsReport): number {
    const max = Math.max(...c.rows.map((r) => Number(r.amount)));
    return max > 0 ? Math.max(1, (Number(amount) / max) * 100) : 0;
  }

  protected loadCollections(): void {
    this.run(this.api.collectionsReport(this.from, this.to, this.groupBy), (r) => this.collections.set(r));
  }

  protected loadOutstanding(): void {
    this.run(this.api.outstandingReport(), (r) => this.outstanding.set(r));
  }

  protected async downloadCollections(): Promise<void> {
    const c = this.collections();
    if (c) {
      await this.save('/internal/v1/admin/reports/collections.csv', { from: c.from, to: c.to, groupBy: c.groupBy });
    }
  }

  protected async downloadOutstanding(): Promise<void> {
    await this.save('/internal/v1/admin/reports/outstanding.csv');
  }

  private async save(path: string, params: Record<string, string> = {}): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.download(path, params);
    } catch (e) {
      this.error.set(describeError(e));
    } finally {
      this.busy.set(false);
    }
  }

  private run<T>(call: import('rxjs').Observable<T>, ok: (value: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (v) => {
        this.busy.set(false);
        ok(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
