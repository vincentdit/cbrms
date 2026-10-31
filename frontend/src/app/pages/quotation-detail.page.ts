import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, Decision, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Quotation } from '../core/models';
import { DecisionComponent } from './decision.component';
import { QuotationCardComponent } from './quotation-card.component';

@Component({
  selector: 'app-quotation-detail',
  imports: [RouterLink, QuotationCardComponent, DecisionComponent],
  template: `
    <p class="back"><a routerLink="/quotations">Back to quotations</a></p>
    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }
    @if (quotation(); as q) {
      <app-quotation-card [quotation]="q" [link]="false" />
      @if (q.status === 'Requested') {
        @if (auth.canApprove()) {
          <app-decision [subjectId]="q.quotationId" kind="QUOTATION" (decided)="decided($event)" />
        } @else {
          <p class="notice">A bill approver must approve this quotation before its milestones can be billed.</p>
        }
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class QuotationDetailPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  readonly id = input.required<string>();
  protected readonly quotation = signal<Quotation | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  protected decided(d: Decision): void {
    this.done.set(d === 'APPROVED' ? 'Quotation approved. The platform can now bill its milestones.' : 'Quotation rejected.');
    this.load(this.id());
  }

  private load(id: string): void {
    this.api.quotation(id).subscribe({ next: (q) => this.quotation.set(q), error: (e) => this.error.set(describeError(e)) });
  }
}
