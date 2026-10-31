import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, describeError } from '../core/api.service';
import { UnallocatedPayment } from '../core/models';
import { MoneyPipe } from '../shared/format';

/** Returns unallocated money to the person who paid it. A second officer approves; the bank reference closes the case. */
@Component({
  selector: 'app-refund-payment',
  imports: [FormsModule, MoneyPipe],
  template: `
    <form class="action-form" (ngSubmit)="submit()" #f="ngForm">
      <p>
        Pay back money from GePG payment <span class="cn">{{ payment().transactionId }}</span>. Up to
        <strong>{{ payment().unallocated | money }}</strong> is available. The amount is set aside at once, so it cannot also be moved to a bill.
      </p>
      <div class="form-grid">
        <div>
          <label [for]="'rf-amt-' + payment().paymentId">Amount (TZS)</label>
          <input [id]="'rf-amt-' + payment().paymentId" name="amount" [(ngModel)]="amount" required inputmode="decimal" pattern="[0-9]+(\\.[0-9]{1,2})?" />
        </div>
        <div>
          <label [for]="'rf-acc-' + payment().paymentId">Account or mobile number</label>
          <input [id]="'rf-acc-' + payment().paymentId" name="account" [(ngModel)]="account" required maxlength="40" autocomplete="off" />
        </div>
        <div class="span-2">
          <label [for]="'rf-name-' + payment().paymentId">Account holder's name</label>
          <input [id]="'rf-name-' + payment().paymentId" name="name" [(ngModel)]="name" required maxlength="200" />
        </div>
      </div>
      <label [for]="'rf-why-' + payment().paymentId">Why is it being returned?</label>
      <textarea [id]="'rf-why-' + payment().paymentId" name="why" rows="2" maxlength="500" [(ngModel)]="reason" required
                placeholder="For example: paid to a wrong control number; payer's letter of 30 Oct"></textarea>
      @if (error()) {
        <p class="form-error" role="alert">{{ error() }}</p>
      }
      <div class="actions">
        <button type="submit" class="primary" [disabled]="busy() || f.invalid">Request refund</button>
        <button type="button" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>
  `,
})
export class RefundPaymentComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly payment = input.required<UnallocatedPayment>();
  readonly done = output<string>();
  readonly cancel = output<void>();
  protected amount = '';
  protected account = '';
  protected name = '';
  protected reason = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.amount = this.payment().unallocated;
  }

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    const p = this.payment();
    this.api.refundPayment(p.paymentId, this.amount.trim(), this.reason.trim(), this.name.trim(), this.account.trim(), p.caseId).subscribe({
      next: () => {
        this.busy.set(false);
        this.done.emit(`Refund from ${p.transactionId} requested. A bill approver must approve it; then record the bank reference under Refunds.`);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
