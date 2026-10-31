import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { CaseRow, UnallocatedPayment } from '../core/models';
import { CASE_TYPES, ControlNumberPipe, EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';
import { MoveToBillComponent } from './move-to-bill.component';
import { RefundPaymentComponent } from './refund-payment.component';

@Component({
  selector: 'app-cases',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, ControlNumberPipe, StateComponent, MoveToBillComponent, RefundPaymentComponent],
  template: `
    <header class="page-head">
      <h1>Exceptions</h1>
      <p class="lede">
        Payments and submissions CBRMS could not settle on its own. Take a case, resolve it, and close it with what was done.
        Money is only ever moved by a correction that a second officer approves.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (unallocated(); as money) {
      @if (money.length > 0) {
        <section class="block" aria-labelledby="money-title">
          <h2 id="money-title">Money not applied to any bill</h2>
          <p class="lede">Verified GePG payments in suspense, or freed by a reversal. Move each to the right bill, or refund it to the payer; the bank reference then closes its case.</p>
          <ul class="approval-list">
            @for (p of money; track p.paymentId) {
              <li>
                <div class="approval-facts">
                  <strong class="cn">{{ p.transactionId }}</strong>
                  <span class="approval-amount">{{ p.unallocated | money }}</span>
                  <span class="sub">
                    Paid to control number {{ p.controlNumber | controlNumber }}@if (p.channel) { via {{ p.channel }}}, received {{ p.receivedAt | eat }}.
                    @if (p.unallocated !== p.amount) { Part of a {{ p.amount | money }} payment. }
                  </span>
                </div>
                @if (p.refundsToPayOut > 0) {
                  <p class="notice">Part of this payment is in an approved refund waiting for the bank reference (see Refunds).</p>
                }
                @if (p.openCorrectionId) {
                  <p class="notice">A correction or refund for this payment is waiting for approval.</p>
                } @else if (canAct()) {
                  @if (moving() === p.paymentId) {
                    <app-move-to-bill [payment]="p" (done)="finished($event)" (cancel)="moving.set(null)" />
                  } @else if (refunding() === p.paymentId) {
                    <app-refund-payment [payment]="p" (done)="finished($event)" (cancel)="refunding.set(null)" />
                  } @else {
                    <div class="actions">
                      <button type="button" class="primary" (click)="moving.set(p.paymentId)">Move to a bill</button>
                      <button type="button" (click)="refunding.set(p.paymentId)">Refund to payer</button>
                    </div>
                  }
                }
              </li>
            }
          </ul>
        </section>
      }
    }

    <section class="block" aria-labelledby="cases-title">
      <h2 id="cases-title">Cases</h2>
      <div class="filters">
        <div class="field narrow">
          <label for="status">Show</label>
          <select id="status" [(ngModel)]="status" (ngModelChange)="load()">
            <option value="OPEN">Open and in progress</option>
            <option value="CLOSED">Closed</option>
            <option value="ALL">All</option>
          </select>
        </div>
      </div>

      @if (rows(); as list) {
        @if (list.length === 0) {
          <p class="all-clear">{{ status === 'OPEN' ? 'No open exceptions.' : 'No exceptions to show.' }}</p>
        } @else {
          <ul class="case-cards">
            @for (c of list; track c.caseId) {
              <li [attr.data-status]="c.status">
                <div class="case-main">
                  <div>
                    <strong>{{ type(c.type) }}</strong>
                    <span class="sub">{{ c.details }}</span>
                    <span class="sub">
                      @if (c.billId) {
                        Bill <a [routerLink]="['/bills', c.billId]">{{ c.sourceReference }}</a>
                      } @else {
                        No bill · GePG reference <span class="cn">{{ c.reference }}</span>
                      }
                      · opened {{ c.openedAt | eat }}@if (c.dueDate && c.status !== 'CLOSED') { · due {{ c.dueDate | eat: false }}}
                      @if (c.owner) { · owner {{ c.owner }}}
                    </span>
                    @if (c.status === 'CLOSED') {
                      <span class="sub">Closed {{ c.closedAt | eat }}: {{ c.closureReason }}@if (c.evidenceRef) { (evidence {{ c.evidenceRef }})}</span>
                    }
                  </div>
                  <div class="case-side">
                    @if (c.value) {
                      <span class="approval-amount">{{ c.value | money: c.currency || 'TZS' }}</span>
                    }
                    <app-state [value]="c.status" />
                  </div>
                </div>
                @if (c.status !== 'CLOSED' && canAct()) {
                  @if (closing() === c.caseId) {
                    <form class="action-form" (ngSubmit)="close(c)">
                      <label [for]="'res-' + c.caseId">How was it resolved?</label>
                      <textarea [id]="'res-' + c.caseId" name="res" rows="2" maxlength="500" [(ngModel)]="resolution" required></textarea>
                      <label [for]="'ev-' + c.caseId">Evidence reference
                        <span class="optional">{{ c.type === 'UNMATCHED_PAYMENT' ? '(required if the money was returned)' : '(optional)' }}</span></label>
                      <input [id]="'ev-' + c.caseId" name="ev" maxlength="100" [(ngModel)]="evidence" placeholder="Bank return, letter or ticket reference" />
                      @if (formError()) {
                        <p class="form-error" role="alert">{{ formError() }}</p>
                      }
                      <div class="actions">
                        <button type="submit" class="primary" [disabled]="busy() || resolution.trim().length < 10">Close case</button>
                        <button type="button" (click)="closing.set(null)">Cancel</button>
                      </div>
                    </form>
                  } @else {
                    <div class="actions">
                      @if (c.status === 'OPEN') {
                        <button type="button" [disabled]="busy()" (click)="take(c)">Take this case</button>
                      }
                      <button type="button" [disabled]="busy()" (click)="startClose(c)">Close with resolution</button>
                    </div>
                  }
                }
              </li>
            }
          </ul>
        }
      } @else if (!error()) {
        <p class="loading">Loading…</p>
      }
    </section>
  `,
})
export class CasesPage {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  protected status = 'OPEN';
  protected readonly rows = signal<CaseRow[] | null>(null);
  protected readonly unallocated = signal<UnallocatedPayment[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly moving = signal<string | null>(null);
  protected readonly refunding = signal<string | null>(null);
  protected readonly closing = signal<string | null>(null);
  protected readonly canAct = computed(() => this.auth.isOfficer() || this.auth.canApprove());
  protected resolution = '';
  protected evidence = '';

  constructor() {
    this.load();
  }

  protected type(t: string): string {
    return CASE_TYPES[t] ?? t;
  }

  protected finished(message: string): void {
    this.moving.set(null);
    this.refunding.set(null);
    this.done.set(message);
    this.load();
  }

  protected take(c: CaseRow): void {
    this.busy.set(true);
    this.api.takeCase(c.caseId).subscribe({
      next: () => {
        this.busy.set(false);
        this.done.set('You now own this case.');
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  protected startClose(c: CaseRow): void {
    this.closing.set(c.caseId);
    this.resolution = '';
    this.evidence = '';
    this.formError.set(null);
  }

  protected close(c: CaseRow): void {
    this.busy.set(true);
    this.formError.set(null);
    this.api.closeCase(c.caseId, this.resolution.trim(), this.evidence.trim() || null).subscribe({
      next: () => {
        this.busy.set(false);
        this.closing.set(null);
        this.done.set('Case closed.');
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.formError.set(describeError(e));
      },
    });
  }

  load(): void {
    this.error.set(null);
    this.api.cases(this.status).subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
    this.api.unallocatedPayments().subscribe({ next: (r) => this.unallocated.set(r), error: () => this.unallocated.set([]) });
  }
}
