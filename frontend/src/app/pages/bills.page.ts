import { Component, inject, input, signal, effect, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { BillRow, Page, PlatformRow } from '../core/models';
import { ControlNumberPipe, EatPipe, MoneyPipe, STATES } from '../shared/format';
import { StateComponent } from '../shared/state.component';

@Component({
  selector: 'app-bills',
  imports: [FormsModule, RouterLink, MoneyPipe, ControlNumberPipe, EatPipe, StateComponent],
  template: `
    <header class="page-head">
      <h1>Bills</h1>
      <p class="lede">Every bill issued through CBRMS. Search by client, source reference or control number.</p>
    </header>

    <form class="filters" (ngSubmit)="apply()" role="search">
      <div class="field grow">
        <label for="q">Search</label>
        <input id="q" name="q" [(ngModel)]="q" placeholder="Client, LAB-2026-0001 or 9912 3456 7890" />
      </div>
      <div class="field">
        <label for="source">Platform</label>
        <select id="source" name="source" [(ngModel)]="p">
          <option value="">All platforms</option>
          @for (pl of platforms(); track pl.code) {
            <option [value]="pl.code">{{ pl.code.toUpperCase() }}: {{ pl.name }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="collection">Payment</label>
        <select id="collection" name="collection" [(ngModel)]="c">
          <option value="">Any</option>
          @for (s of collectionStates; track s) {
            <option [value]="s">{{ label(s) }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="registration">GePG</label>
        <select id="registration" name="registration" [(ngModel)]="r">
          <option value="">Any</option>
          @for (s of registrationStates; track s) {
            <option [value]="s">{{ label(s) }}</option>
          }
          <option value="Submitted,AwaitingControlNumber">Waiting for control number</option>
        </select>
      </div>
      <button type="submit" class="primary">Search</button>
    </form>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (page(); as p) {
      @if (p.items.length === 0) {
        <p class="empty">No bills match. Clear the filters or search for a different reference.</p>
      } @else {
        <div class="table-wrap">
          <table class="data">
            <caption class="visually-hidden">Bills, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Client and reference</th>
                <th scope="col">Control number</th>
                <th scope="col" class="num">Amount</th>
                <th scope="col" class="num">Paid</th>
                <th scope="col">GePG</th>
                <th scope="col">Payment</th>
                <th scope="col">Created</th>
              </tr>
            </thead>
            <tbody>
              @for (b of p.items; track b.billId) {
                <tr>
                  <td>
                    <a [routerLink]="['/bills', b.billId]" class="row-link">{{ b.payerName }}</a>
                    <span class="sub">{{ b.sourceSystem.toUpperCase() }} {{ b.sourceReference }}</span>
                  </td>
                  <td class="cn">{{ b.controlNumber | controlNumber }}</td>
                  <td class="num">{{ b.total | money: b.currency }}</td>
                  <td class="num" [class.muted]="isZero(b.paid)">{{ b.paid | money: b.currency }}</td>
                  <td><app-state [value]="b.preparationState === 'Approved' ? b.externalRegistrationStatus : b.preparationState" /></td>
                  <td><app-state [value]="b.collectionState" /></td>
                  <td class="nowrap">{{ b.createdAt | eat }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <nav class="pager" aria-label="Pages">
          <span>{{ from(p) }}–{{ to(p) }} of {{ p.total }}</span>
          <button type="button" (click)="go(p.page - 1)" [disabled]="p.page === 0">Previous</button>
          <button type="button" (click)="go(p.page + 1)" [disabled]="to(p) >= p.total">Next</button>
        </nav>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class BillsPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  /** Bound from the URL query string, so filtered views can be linked and bookmarked. */
  readonly search = input<string>('');
  readonly collection = input<string>('');
  readonly registration = input<string>('');
  readonly source = input<string>('');
  readonly pageNo = input<string>('0', { alias: 'page' });

  protected q = '';
  protected c = '';
  protected r = '';
  protected p = '';
  protected readonly platforms = signal<PlatformRow[]>([]);
  protected readonly page = signal<Page<BillRow> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly collectionStates = ['Unpaid', 'PartiallyPaid', 'Paid', 'Overpaid'];
  protected readonly registrationStates = ['Accepted', 'AckUnknown', 'Rejected', 'Cancelled', 'NotSubmitted'];

  constructor() {
    this.api.platforms().subscribe({ next: (list) => this.platforms.set(list), error: () => this.platforms.set([]) });
    effect(() => {
      const query = { search: this.search() ?? '', collection: this.collection() ?? '', registration: this.registration() ?? '', source: this.source() ?? '', page: Number(this.pageNo() ?? 0) };
      untracked(() => {
        this.q = query.search;
        this.c = query.collection;
        this.r = query.registration;
        this.p = query.source;
        this.load(query);
      });
    });
  }

  protected isZero(v: string): boolean {
    return Number(v) === 0;
  }

  protected label(s: string): string {
    return STATES[s]?.label ?? s;
  }

  protected apply(): void {
    this.navigate(0);
  }

  protected go(page: number): void {
    this.navigate(page);
  }

  protected from(p: Page<BillRow>): number {
    return p.total === 0 ? 0 : p.page * p.size + 1;
  }

  protected to(p: Page<BillRow>): number {
    return Math.min(p.total, (p.page + 1) * p.size);
  }

  private navigate(page: number): void {
    this.router.navigate(['/bills'], {
      queryParams: { search: this.q || null, collection: this.c || null, registration: this.r || null, source: this.p || null, page: page || null },
    });
  }

  private load(query: { search: string; collection: string; registration: string; source: string; page: number }): void {
    this.error.set(null);
    this.api.bills({ ...query, size: 25 }).subscribe({ next: (p) => this.page.set(p), error: (e) => this.error.set(describeError(e)) });
  }
}
