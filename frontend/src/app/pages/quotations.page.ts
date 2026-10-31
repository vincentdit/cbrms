import { Component, inject, signal } from '@angular/core';
import { ApiService, describeError } from '../core/api.service';
import { Quotation } from '../core/models';
import { QuotationCardComponent } from './quotation-card.component';

@Component({
  selector: 'app-quotations',
  imports: [QuotationCardComponent],
  template: `
    <header class="page-head">
      <h1>Quotations</h1>
      <p class="lede">
        Consultancy prices agreed per engagement. A platform submits the quotation with its payment milestones, a bill approver
        approves it, and each milestone can then be billed once. CBRMS takes the amount from the quotation, never from the platform.
      </p>
    </header>

    <div class="chips" role="group" aria-label="Show">
      @for (f of filters; track f.value) {
        <button type="button" class="chip" [class.on]="status() === f.value" [attr.aria-pressed]="status() === f.value" (click)="show(f.value)">
          {{ f.label }}
        </button>
      }
    </div>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (rows(); as list) {
      @if (list.length === 0) {
        <p class="empty">No quotations in this list.</p>
      } @else {
        <div class="quote-list">
          @for (q of list; track q.quotationId) {
            <app-quotation-card [quotation]="q" />
          }
        </div>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class QuotationsPage {
  private readonly api = inject(ApiService);
  protected readonly filters = [
    { value: 'Approved', label: 'Approved' },
    { value: 'Requested', label: 'Waiting for approval' },
    { value: 'Rejected', label: 'Rejected' },
    { value: '', label: 'All' },
  ];
  protected readonly status = signal('Approved');
  protected readonly rows = signal<Quotation[] | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected show(status: string): void {
    this.status.set(status);
    this.load();
  }

  private load(): void {
    this.rows.set(null);
    this.error.set(null);
    this.api.quotations(this.status()).subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
  }
}
