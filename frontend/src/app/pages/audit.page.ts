import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, describeError } from '../core/api.service';
import { AuditPage, AuditQuery, AuditRow } from '../core/models';
import { EatPipe } from '../shared/format';

const TODAY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());
const WEEK_AGO = (() => {
  const d = new Date(`${TODAY}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 6);
  return d.toISOString().slice(0, 10);
})();

/** Read-only view of the append-only audit trail (FR28). */
@Component({
  selector: 'app-audit',
  imports: [FormsModule, EatPipe],
  template: `
    <header class="page-head">
      <h1>Audit trail</h1>
      <p class="lede">
        Every approval, correction, catalogue change and export, with who did it, to which record, when and why.
        The trail cannot be edited or deleted. Downloads of it are recorded too.
      </p>
    </header>

    <form class="filters audit-filters" (ngSubmit)="search(0)">
      <div class="field">
        <label for="a-from">From</label>
        <input id="a-from" name="from" type="date" [max]="q.to" [(ngModel)]="q.from" required />
      </div>
      <div class="field">
        <label for="a-to">To</label>
        <input id="a-to" name="to" type="date" [min]="q.from" [max]="today" [(ngModel)]="q.to" required />
      </div>
      <div class="field">
        <label for="a-actor">Person or system</label>
        <input id="a-actor" name="actor" [(ngModel)]="q.actor" placeholder="finance.maker, lims, gepg" />
      </div>
      <div class="field">
        <label for="a-action">Action</label>
        <select id="a-action" name="action" [(ngModel)]="q.action">
          <option value="">Any</option>
          @for (a of actions(); track a) {
            <option [value]="a">{{ label(a) }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="a-object">Record</label>
        <input id="a-object" name="object" [(ngModel)]="q.object" placeholder="bill:…, catalogue:LIMS-CHEM-001 or an ID" />
      </div>
      <div class="field">
        <label for="a-corr">Correlation ID</label>
        <input id="a-corr" name="corr" [(ngModel)]="q.correlationId" />
      </div>
      <button type="submit" class="primary" [disabled]="busy()">Search</button>
      <button type="button" [disabled]="busy() || !result()" (click)="download()">Download CSV</button>
    </form>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (result(); as r) {
      @if (r.items.length === 0) {
        <p class="empty">Nothing recorded for this search.</p>
      } @else {
        <div class="table-wrap">
          <table class="data">
            <caption class="visually-hidden">Audit events, newest first</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">Action</th>
                <th scope="col">Record</th>
                <th scope="col">Reason</th>
                <th scope="col"><span class="visually-hidden">Details</span></th>
              </tr>
            </thead>
            <tbody>
              @for (e of r.items; track e.id) {
                <tr>
                  <td class="nowrap">{{ e.occurredAt | eat }}</td>
                  <td>{{ e.actorName ?? e.actor }}@if (e.actorName) {<span class="sub cn">{{ e.actor }}</span>}</td>
                  <td>{{ label(e.action) }}</td>
                  <td class="cn audit-ref">{{ e.objectRef }}</td>
                  <td>{{ e.reason }}</td>
                  <td>
                    @if (e.details || e.correlationId) {
                      <button type="button" class="link-button" [attr.aria-expanded]="open() === e.id" (click)="toggle(e)">Details</button>
                    }
                  </td>
                </tr>
                @if (open() === e.id) {
                  <tr>
                    <td colspan="6">
                      @if (e.correlationId) {
                        <p class="sub">Correlation ID <span class="cn">{{ e.correlationId }}</span> ·
                          <button type="button" class="link-button" (click)="byCorrelation(e.correlationId)">Show everything in this request</button></p>
                      }
                      @if (e.details) {
                        <pre class="audit-details">{{ pretty(e.details) }}</pre>
                      }
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <nav class="pager" aria-label="Pages">
          <span>{{ r.page * r.size + 1 }}–{{ min(r.total, (r.page + 1) * r.size) }} of {{ r.total }}</span>
          <button type="button" [disabled]="r.page === 0 || busy()" (click)="search(r.page - 1)">Previous</button>
          <button type="button" [disabled]="(r.page + 1) * r.size >= r.total || busy()" (click)="search(r.page + 1)">Next</button>
        </nav>
      }
    } @else if (busy()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class AuditPageComponent {
  private readonly api = inject(ApiService);
  protected readonly today = TODAY;
  protected q: AuditQuery = { from: WEEK_AGO, to: TODAY, actor: '', action: '', object: '', correlationId: '' };
  protected readonly actions = signal<string[]>([]);
  protected readonly result = signal<AuditPage | null>(null);
  protected readonly open = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.auditActions().subscribe({ next: (a) => this.actions.set(a) });
    this.search(0);
  }

  /** CATALOGUE_CHANGE_APPLIED -> Catalogue change applied */
  protected label(action: string): string {
    const words = action.toLowerCase().replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
  }

  protected min(a: number, b: number): number {
    return Math.min(a, b);
  }

  protected toggle(e: AuditRow): void {
    this.open.set(this.open() === e.id ? null : e.id);
  }

  protected pretty(json: string): string {
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }

  protected byCorrelation(id: string): void {
    this.q = { ...this.q, actor: '', action: '', object: '', correlationId: id };
    this.search(0);
  }

  protected search(page: number): void {
    this.busy.set(true);
    this.error.set(null);
    this.open.set(null);
    this.api.audit(this.q, page).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.result.set(r);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }

  protected async download(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.download('/internal/v1/admin/audit/export.csv', { ...this.q });
    } catch (e) {
      this.error.set(describeError(e));
    } finally {
      this.busy.set(false);
    }
  }
}
