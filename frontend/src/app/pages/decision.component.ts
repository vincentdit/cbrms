import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, Decision, describeError } from '../core/api.service';
import { ApprovalKind } from '../core/models';

const NOUN: Record<ApprovalKind, string> = {
  BILL: 'bill',
  CANCELLATION: 'cancellation',
  EXPIRY_CHANGE: 'new deadline',
  REFUND: 'refund',
};

/** Approve or reject any maker-checker item. The server refuses the maker's own request; the form says why. */
@Component({
  selector: 'app-decision',
  imports: [FormsModule],
  template: `
    <section class="decision" [attr.aria-labelledby]="'decision-title-' + subjectId()">
      <h2 [id]="'decision-title-' + subjectId()">Your decision</h2>
      <label [for]="'reason-' + subjectId()">Reason <span class="optional">(required to reject)</span></label>
      <textarea [id]="'reason-' + subjectId()" rows="2" [(ngModel)]="reason" maxlength="500"></textarea>
      @if (error()) {
        <p class="form-error" role="alert">{{ error() }}</p>
      }
      <div class="actions">
        <button type="button" class="primary" [disabled]="busy()" (click)="decide('APPROVED')">Approve {{ noun() }}</button>
        <button type="button" class="danger" [disabled]="busy() || !reason.trim()" (click)="decide('REJECTED')">Reject {{ noun() }}</button>
      </div>
    </section>
  `,
})
export class DecisionComponent {
  private readonly api = inject(ApiService);
  readonly subjectId = input.required<string>();
  readonly kind = input<ApprovalKind>('BILL');
  readonly decided = output<Decision>();
  protected readonly noun = computed(() => NOUN[this.kind()]);
  protected reason = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  decide(decision: Decision): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.decideAny(this.kind(), this.subjectId(), decision, this.reason.trim() || null).subscribe({
      next: () => {
        this.busy.set(false);
        this.decided.emit(decision);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
