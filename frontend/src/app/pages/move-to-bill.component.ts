import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, describeError } from '../core/api.service';
import { BillRow, UnallocatedPayment } from '../core/models';
import { ControlNumberPipe, MoneyPipe } from '../shared/format';

/** Finds the right bill for unallocated money and requests the move. A second officer approves it. */
@Component({
  selector: 'app-move-to-bill',
  imports: [FormsModule, MoneyPipe, ControlNumberPipe],
  template: `
    <form class="action-form" (ngSubmit)="find()">
      <p>
        Move <strong>{{ payment().unallocated | money }}</strong> from GePG payment <span class="cn">{{ payment().transactionId }}</span>
        to the bill the client meant to pay. The bill's payment rules apply; any excess is held as credit.
      </p>
      <label for="mv-q-{{ payment().paymentId }}">Find the bill by control number, reference or client</label>
      <div class="inline-search">
        <input [id]="'mv-q-' + payment().paymentId" name="q" [(ngModel)]="q" required placeholder="9912 3456 7890 or LAB-2026-0101" />
        <button type="submit" [disabled]="busy() || !q.trim()">Find</button>
      </div>
      @if (results(); as list) {
        @if (list.length === 0) {
          <p class="hint">No issued bill matches.</p>
        } @else {
          <fieldset class="bill-choices">
            <legend>Choose the bill</legend>
            @for (b of list; track b.billId) {
              <label>
                <input type="radio" name="bill" [value]="b.billId" [(ngModel)]="billId" />
                <span><strong>{{ b.payerName }}</strong> · {{ b.sourceSystem.toUpperCase() }} {{ b.sourceReference }} ·
                  {{ b.controlNumber | controlNumber }} · {{ b.total | money: b.currency }} (balance {{ b.balance | money: b.currency }})</span>
              </label>
            }
          </fieldset>
        }
      }
    </form>
    @if (billId) {
      <form class="action-form" (ngSubmit)="request()">
        <label [for]="'mv-why-' + payment().paymentId">Why does this payment belong to that bill?</label>
        <textarea [id]="'mv-why-' + payment().paymentId" name="why" rows="2" maxlength="500" [(ngModel)]="reason" required
                  placeholder="For example: client's bank slip shows the old control number"></textarea>
        @if (error()) {
          <p class="form-error" role="alert">{{ error() }}</p>
        }
        <div class="actions">
          <button type="submit" class="primary" [disabled]="busy() || !reason.trim()">Request the move</button>
          <button type="button" (click)="cancel.emit()">Cancel</button>
        </div>
      </form>
    } @else if (error()) {
      <p class="form-error" role="alert">{{ error() }}</p>
    }
  `,
})
export class MoveToBillComponent {
  private readonly api = inject(ApiService);
  readonly payment = input.required<UnallocatedPayment>();
  readonly done = output<string>();
  readonly cancel = output<void>();
  protected q = '';
  protected billId = '';
  protected reason = '';
  protected readonly results = signal<BillRow[] | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected find(): void {
    this.busy.set(true);
    this.error.set(null);
    this.billId = '';
    this.api.bills({ search: this.q.trim(), registration: 'Accepted', size: 10 }).subscribe({
      next: (p) => {
        this.busy.set(false);
        this.results.set(p.items);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  protected request(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.requestCorrection('ALLOCATE', this.payment().paymentId, this.billId, this.reason.trim(), this.payment().caseId).subscribe({
      next: () => {
        this.busy.set(false);
        this.done.emit(`Move of ${this.payment().transactionId} requested. A bill approver must approve it under Approvals.`);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
