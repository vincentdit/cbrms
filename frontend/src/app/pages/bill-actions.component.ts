import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, describeError } from '../core/api.service';
import { Activity, Bill, CreditRow } from '../core/models';
import { ControlNumberPipe, MoneyPipe } from '../shared/format';

type Action = 'cancel' | 'deadline' | 'refund' | null;

/**
 * Officer actions on an issued bill. Each one only creates a request: a second officer approves it on the
 * Approvals page, and a cancellation or new deadline takes effect only when GePG confirms it.
 */
@Component({
  selector: 'app-bill-actions',
  imports: [FormsModule, MoneyPipe, ControlNumberPipe],
  template: `
    @if (canCancel() || canMoveDeadline() || refundable().length > 0) {
      <section class="block bill-actions" aria-labelledby="actions-title">
        <h2 id="actions-title">Request a change</h2>
        <p class="lede">A second officer approves each request before anything changes.</p>
        <div class="actions">
          @if (canCancel()) {
            <button type="button" [class.on]="open() === 'cancel'" (click)="toggle('cancel')">Cancel this bill</button>
          }
          @if (canMoveDeadline()) {
            <button type="button" [class.on]="open() === 'deadline'" (click)="toggle('deadline')">Give a new deadline</button>
          }
          @if (refundable().length > 0) {
            <button type="button" [class.on]="open() === 'refund'" (click)="toggle('refund')">Refund client credit</button>
          }
        </div>

        @if (open() === 'cancel') {
          <form class="action-form" (ngSubmit)="cancel()">
            <p>
              GePG will be asked to cancel control number {{ bill().controlNumber | controlNumber }}. Afterwards
              {{ bill().sourceSystem.toUpperCase() }} can send a replacement bill for the same request.
            </p>
            <label for="cancel-reason">Why is it being cancelled?</label>
            <textarea id="cancel-reason" name="reason" rows="2" maxlength="500" [(ngModel)]="reason" required></textarea>
            @if (error()) {
              <p class="form-error" role="alert">{{ error() }}</p>
            }
            <div class="actions">
              <button type="submit" class="danger" [disabled]="busy() || !reason.trim()">Request cancellation</button>
            </div>
          </form>
        }

        @if (open() === 'deadline') {
          <form class="action-form" (ngSubmit)="deadline()">
            <label for="new-due">New last day to pay</label>
            <input id="new-due" name="newDue" type="date" [min]="minDue()" [(ngModel)]="newDue" required />
            <label for="deadline-reason">Reason</label>
            <textarea id="deadline-reason" name="reason" rows="2" maxlength="500" [(ngModel)]="reason" required></textarea>
            @if (error()) {
              <p class="form-error" role="alert">{{ error() }}</p>
            }
            <div class="actions">
              <button type="submit" class="primary" [disabled]="busy() || !newDue || !reason.trim()">Request new deadline</button>
            </div>
          </form>
        }

        @if (open() === 'refund') {
          <form class="action-form" (ngSubmit)="refund()">
            <label for="credit">Credit to refund</label>
            <select id="credit" name="credit" [(ngModel)]="creditId" (ngModelChange)="amount = creditAmount()">
              @for (c of refundable(); track c.creditId) {
                <option [value]="c.creditId">{{ c.amount | money: bill().currency }} held since overpayment</option>
              }
            </select>
            <label for="refund-amount">Amount to pay back ({{ bill().currency }})</label>
            <input id="refund-amount" name="amount" inputmode="decimal" [(ngModel)]="amount" required pattern="[0-9]+(\\.[0-9]{1,2})?" />
            <label for="refund-reason">Reason</label>
            <textarea id="refund-reason" name="reason" rows="2" maxlength="500" [(ngModel)]="reason" required></textarea>
            @if (error()) {
              <p class="form-error" role="alert">{{ error() }}</p>
            }
            <div class="actions">
              <button type="submit" class="primary" [disabled]="busy() || !amount || !reason.trim()">Request refund</button>
            </div>
          </form>
        }
      </section>
    }
  `,
})
export class BillActionsComponent {
  private readonly api = inject(ApiService);
  readonly bill = input.required<Bill>();
  readonly activity = input.required<Activity>();
  readonly done = output<string>();

  protected readonly open = signal<Action>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected reason = '';
  protected newDue = '';
  protected creditId = '';
  protected amount = '';

  private readonly openChange = computed(() =>
    this.activity().changes.some((c) => ['Requested', 'SentToGepg', 'OutcomeUnknown'].includes(c.status)),
  );
  private readonly issued = computed(() => this.bill().externalRegistrationStatus === 'Accepted' && !this.openChange());
  protected readonly canCancel = computed(() => this.issued() && this.bill().collectionState === 'Unpaid');
  protected readonly canMoveDeadline = computed(
    () => this.issued() && ['Unpaid', 'PartiallyPaid'].includes(this.bill().collectionState),
  );
  protected readonly refundable = computed<CreditRow[]>(() =>
    this.activity().credits.filter((c) => c.status === 'AVAILABLE' && (!c.refundStatus || ['Rejected', 'Executed'].includes(c.refundStatus))),
  );
  protected readonly minDue = computed(() => {
    const due = new Date(`${this.bill().dueDate}T00:00:00Z`);
    due.setUTCDate(due.getUTCDate() + 1);
    return due.toISOString().slice(0, 10);
  });

  protected toggle(action: Exclude<Action, null>): void {
    this.open.set(this.open() === action ? null : action);
    this.error.set(null);
    this.reason = '';
    if (action === 'refund') {
      this.creditId = this.refundable()[0]?.creditId ?? '';
      this.amount = this.creditAmount();
    }
  }

  protected creditAmount(): string {
    return this.refundable().find((c) => c.creditId === this.creditId)?.amount ?? '';
  }

  protected cancel(): void {
    this.submit(this.api.requestCancellation(this.bill().billId, this.reason.trim()), 'Cancellation requested. A bill approver must now approve it.');
  }

  protected deadline(): void {
    this.submit(
      this.api.requestExpiryChange(this.bill().billId, this.newDue, this.reason.trim()),
      'New deadline requested. A bill approver must now approve it.',
    );
  }

  protected refund(): void {
    this.submit(this.api.requestRefund(this.creditId, this.amount.trim(), this.reason.trim()), 'Refund requested. A bill approver must now approve it.');
  }

  private submit(call: import('rxjs').Observable<unknown>, message: string): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.open.set(null);
        this.done.emit(message);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
