import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { RefundRow } from '../core/models';
import { EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';

/** Refunds of client credit. Requests are approved on the Approvals page; this page records the bank payout. */
@Component({
  selector: 'app-refunds',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, StateComponent],
  template: `
    <header class="page-head">
      <h1>Refunds</h1>
      <p class="lede">
        Overpayments are held as client credit until refunded. A refund is requested from the bill, approved by a second officer,
        then paid by the bank. Record the bank reference here once the money has left.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    <div class="chips" role="group" aria-label="Show">
      @for (f of filters; track f.value) {
        <button type="button" class="chip" [class.on]="status() === f.value" [attr.aria-pressed]="status() === f.value" (click)="show(f.value)">
          {{ f.label }}
        </button>
      }
    </div>

    @if (rows(); as list) {
      @if (list.length === 0) {
        <p class="empty">No refunds in this list.</p>
      } @else {
        <ul class="approval-list">
          @for (r of list; track r.refundId) {
            <li>
              <div class="approval-facts">
                <app-state [value]="r.status" />
                <a [routerLink]="['/bills', r.billId]" class="row-link">{{ r.payerName }}</a>
                <span class="approval-amount">{{ r.amount | money }}</span>
                <span class="sub">{{ r.reason }}. Bill {{ r.sourceReference }}, requested by {{ r.requestedBy }} on {{ r.createdAt | eat }}</span>
                @if (r.executionRef) {
                  <span class="approval-detail">Bank reference {{ r.executionRef }}</span>
                }
                @if (r.failureReason) {
                  <span class="approval-detail">Last payout failed: {{ r.failureReason }}</span>
                }
              </div>
              @if (canExecute(r)) {
                @if (openId() === r.refundId) {
                  <form class="decision" (ngSubmit)="record(r)">
                    <h2>Record the bank payout</h2>
                    <fieldset class="radio-row">
                      <legend>Did the bank pay the client?</legend>
                      <label><input type="radio" name="outcome" value="paid" [(ngModel)]="outcome" /> Yes, paid</label>
                      <label><input type="radio" name="outcome" value="failed" [(ngModel)]="outcome" /> No, it failed</label>
                    </fieldset>
                    @if (outcome === 'paid') {
                      <label for="ref-{{ r.refundId }}">Bank transfer reference</label>
                      <input id="ref-{{ r.refundId }}" name="ref" maxlength="100" [(ngModel)]="reference" required />
                    } @else {
                      <label for="why-{{ r.refundId }}">What went wrong</label>
                      <textarea id="why-{{ r.refundId }}" name="why" rows="2" maxlength="500" [(ngModel)]="failure" required></textarea>
                    }
                    <div class="actions">
                      <button type="submit" class="primary" [disabled]="busy() || (outcome === 'paid' ? !reference.trim() : !failure.trim())">Save</button>
                      <button type="button" (click)="openId.set(null)">Close</button>
                    </div>
                  </form>
                } @else {
                  <div class="actions"><button type="button" class="primary" (click)="start(r)">Record payout</button></div>
                }
              }
            </li>
          }
        </ul>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class RefundsPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly filters = [
    { value: 'Approved', label: 'To pay out' },
    { value: 'ExecutionFailed', label: 'Payout failed' },
    { value: 'Requested', label: 'Waiting for approval' },
    { value: 'Executed', label: 'Paid out' },
    { value: '', label: 'All' },
  ];
  protected readonly status = signal('Approved');
  protected readonly rows = signal<RefundRow[] | null>(null);
  protected readonly openId = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected outcome: 'paid' | 'failed' = 'paid';
  protected reference = '';
  protected failure = '';
  private readonly officer = computed(() => this.auth.isOfficer() || this.auth.canApprove());

  constructor() {
    this.load();
  }

  protected canExecute(r: RefundRow): boolean {
    return this.officer() && (r.status === 'Approved' || r.status === 'ExecutionFailed');
  }

  protected show(status: string): void {
    this.status.set(status);
    this.load();
  }

  protected start(r: RefundRow): void {
    this.openId.set(r.refundId);
    this.outcome = 'paid';
    this.reference = '';
    this.failure = '';
  }

  protected record(r: RefundRow): void {
    this.busy.set(true);
    this.error.set(null);
    const paid = this.outcome === 'paid';
    this.api.recordRefundExecution(r.refundId, paid ? this.reference.trim() : null, paid ? null : this.failure.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.openId.set(null);
        this.done.set(paid ? `Recorded payout of the refund to ${r.payerName}.` : `Recorded a failed payout for ${r.payerName}; an exception was opened.`);
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  private load(): void {
    this.rows.set(null);
    this.api.refunds(this.status() || undefined).subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
  }
}
