import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ReconciliationItem, ReconciliationRun } from '../core/models';
import { EatPipe, MoneyPipe, RUN_REVIEW } from '../shared/format';
import { StateComponent } from '../shared/state.component';

function today(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());
}

@Component({
  selector: 'app-reconciliation',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, StateComponent],
  template: `
    <header class="page-head">
      <h1>Reconciliation</h1>
      <p class="lede">
        Each morning CBRMS compares the previous day's GePG collections with its own payment records. Every difference opens an
        exception. A Finance officer then signs the day off.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (auth.isOfficer() || auth.canApprove()) {
      <form class="filters" (ngSubmit)="run()">
        <div class="field">
          <label for="rec-day">Business day</label>
          <input id="rec-day" name="day" type="date" [max]="maxDay" [(ngModel)]="day" required />
        </div>
        <button type="submit" class="primary" [disabled]="busy() || !day">{{ busy() ? 'Comparing…' : 'Run reconciliation now' }}</button>
      </form>
    }

    @if (runs(); as list) {
      @if (list.length === 0) {
        <p class="empty">No reconciliation has run yet. The scheduled run starts at 06:30.</p>
      } @else {
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th scope="col">Day</th>
                <th scope="col">Status</th>
                <th scope="col" class="num">Matched</th>
                <th scope="col" class="num">Differences</th>
                <th scope="col" class="num">GePG total</th>
                <th scope="col" class="num">CBRMS total</th>
                <th scope="col">Signed off</th>
              </tr>
            </thead>
            <tbody>
              @for (r of list; track r.batchId) {
                <tr [class.selected]="selected()?.batchId === r.batchId">
                  <td>
                    <button type="button" class="link-button" (click)="open(r)">{{ r.businessDate | eat: false }}</button>
                  </td>
                  <td><app-state [value]="r.status" />@if (reviewHint(r.status); as h) { <span class="sub">{{ h }}</span> }</td>
                  <td class="num">{{ r.matched }}</td>
                  <td class="num" [class.strong]="r.exceptions + r.missingInGepg > 0">{{ r.exceptions + r.missingInGepg }}</td>
                  <td class="num">{{ r.externalTotal | money }}</td>
                  <td class="num">{{ r.localTotal | money }}</td>
                  <td>{{ r.reviewer ?? '' }}@if (r.reviewedAt) { <span class="sub">{{ r.reviewedAt | eat }}</span> }</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }

    @if (selected(); as r) {
      <section class="block" aria-labelledby="items-title">
        <h2 id="items-title">Lines for {{ r.businessDate | eat: false }}</h2>
        @if (differences() > 0) {
          <p class="notice notice-alert">
            {{ differences() }} {{ differences() === 1 ? 'line does' : 'lines do' }} not agree. Each has an exception on the
            <a routerLink="/cases">Exceptions</a> page; resolve them before signing off where you can.
          </p>
        } @else if (r.missingInGepg > 0) {
          <p class="notice notice-alert">{{ r.missingInGepg }} CBRMS payment(s) are not in the GePG file. See Exceptions.</p>
        }
        @if (items(); as rows) {
          <div class="table-wrap">
            <table class="data">
              <thead>
                <tr>
                  <th scope="col">GePG payment reference</th>
                  <th scope="col">Control number</th>
                  <th scope="col" class="num">Amount</th>
                  <th scope="col">Result</th>
                </tr>
              </thead>
              <tbody>
                @for (i of rows; track i.reference) {
                  <tr>
                    <td class="cn">{{ i.reference }}</td>
                    <td class="cn">{{ i.controlNumber }}</td>
                    <td class="num">{{ i.amount | money: i.currency }}</td>
                    <td><app-state [value]="i.matchStatus" /></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
        @if (r.status === 'MATCHED' && (auth.isOfficer() || auth.canApprove())) {
          <form class="decision" (ngSubmit)="review(r)">
            <h2>Sign off this day</h2>
            <label for="rev-note">Note <span class="optional">(optional; say what is still open)</span></label>
            <textarea id="rev-note" name="note" rows="2" maxlength="500" [(ngModel)]="note"></textarea>
            <div class="actions">
              <button type="submit" class="primary" [disabled]="busy()">Sign off {{ r.businessDate | eat: false }}</button>
            </div>
          </form>
        } @else if (r.status === 'REVIEWED' && r.note) {
          <p class="notice">Sign-off note: {{ r.note }}</p>
        }
      </section>
    }
  `,
})
export class ReconciliationPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly runs = signal<ReconciliationRun[] | null>(null);
  protected readonly selected = signal<ReconciliationRun | null>(null);
  protected readonly items = signal<ReconciliationItem[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly maxDay = today();
  protected day = today();
  protected note = '';
  protected readonly differences = computed(() => (this.items() ?? []).filter((i) => i.matchStatus !== 'MATCHED').length);

  constructor() {
    this.load();
  }

  protected reviewHint(status: string): string | null {
    return RUN_REVIEW[status]?.label ?? null;
  }

  protected open(r: ReconciliationRun): void {
    this.selected.set(r);
    this.items.set(null);
    this.note = '';
    this.api.reconciliationItems(r.batchId).subscribe({ next: (i) => this.items.set(i), error: (e) => this.error.set(describeError(e)) });
  }

  protected run(): void {
    this.busy.set(true);
    this.error.set(null);
    this.done.set(null);
    this.api.runReconciliation(this.day).subscribe({
      next: (res) => {
        this.busy.set(false);
        const diff = res.exceptions + res.missingInGepg;
        this.done.set(
          res.reusedExisting
            ? 'GePG data for this day has not changed since the last run, so the existing result is shown.'
            : `Compared: ${res.matched} matched, ${diff} ${diff === 1 ? 'difference' : 'differences'}.`,
        );
        this.load(res.batchId);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  protected review(r: ReconciliationRun): void {
    this.busy.set(true);
    this.api.reviewReconciliation(r.batchId, this.note.trim() || null).subscribe({
      next: () => {
        this.busy.set(false);
        this.done.set(`Signed off ${r.businessDate}.`);
        this.load(r.batchId);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  private load(select?: string): void {
    this.api.reconciliationRuns().subscribe({
      next: (list) => {
        this.runs.set(list);
        const pick = list.find((r) => r.batchId === (select ?? this.selected()?.batchId));
        if (pick) {
          this.open(pick);
        }
      },
      error: (e) => this.error.set(describeError(e)),
    });
  }
}
