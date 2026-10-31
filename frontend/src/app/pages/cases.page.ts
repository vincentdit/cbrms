import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { CaseRow } from '../core/models';
import { CASE_TYPES, EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';

@Component({
  selector: 'app-cases',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, StateComponent],
  template: `
    <header class="page-head">
      <h1>Exceptions</h1>
      <p class="lede">Payments and submissions CBRMS could not settle on its own. Each one needs an owner and recorded evidence before it closes.</p>
    </header>

    <div class="filters">
      <div class="field">
        <label for="status">Show</label>
        <select id="status" [(ngModel)]="status" (ngModelChange)="load()">
          <option value="OPEN">Open and in progress</option>
          <option value="CLOSED">Closed</option>
          <option value="ALL">All</option>
        </select>
      </div>
    </div>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (rows(); as list) {
      @if (list.length === 0) {
        <p class="all-clear">{{ status === 'OPEN' ? 'No open exceptions.' : 'No exceptions to show.' }}</p>
      } @else {
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th scope="col">Exception</th>
                <th scope="col">Bill</th>
                <th scope="col" class="num">Value</th>
                <th scope="col">Status</th>
                <th scope="col">Opened</th>
                <th scope="col">Due</th>
              </tr>
            </thead>
            <tbody>
              @for (c of list; track c.caseId) {
                <tr>
                  <td><strong>{{ type(c.type) }}</strong><span class="sub">{{ c.details }}</span></td>
                  <td>
                    @if (c.billId) {
                      <a [routerLink]="['/bills', c.billId]">{{ c.sourceReference }}</a>
                    } @else {
                      <span class="sub">No bill. GePG reference {{ c.reference }}</span>
                    }
                  </td>
                  <td class="num">{{ c.value | money: c.currency || 'TZS' }}</td>
                  <td><app-state [value]="c.status" /></td>
                  <td class="nowrap">{{ c.openedAt | eat }}</td>
                  <td class="nowrap">{{ c.dueDate | eat: false }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class CasesPage {
  private readonly api = inject(ApiService);
  protected status = 'OPEN';
  protected readonly rows = signal<CaseRow[] | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected type(t: string): string {
    return CASE_TYPES[t] ?? t;
  }

  load(): void {
    this.error.set(null);
    this.api.cases(this.status).subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
  }
}
