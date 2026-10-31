import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { CatalogueService } from '../core/models';
import { EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';
import { CatalogueFormComponent } from './catalogue-form.component';

@Component({
  selector: 'app-catalogue',
  imports: [FormsModule, RouterLink, MoneyPipe, EatPipe, StateComponent, CatalogueFormComponent],
  template: `
    <header class="page-head">
      <h1>Catalogue</h1>
      <p class="lede">
        Every service TIRDO bills through CBRMS, the price in force today and the platforms allowed to bill it. Changes are proposed
        here and take effect only after a second officer approves them. Prices are never backdated.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    <div class="filters">
      <div class="field grow">
        <label for="cat-q">Find a service</label>
        <input id="cat-q" name="q" [(ngModel)]="q" (ngModelChange)="query.set($event)" placeholder="Code or name" />
      </div>
      @if (canPropose()) {
        <button type="button" class="primary" (click)="adding.set(!adding())">{{ adding() ? 'Close' : 'Propose a new service' }}</button>
      }
    </div>

    @if (adding()) {
      <app-catalogue-form kind="NEW_SERVICE" (saved)="proposed($event)" (cancel)="adding.set(false)" />
    }

    @if (rows(); as list) {
      <div class="table-wrap">
        <table class="data">
          <caption class="visually-hidden">Services in the catalogue</caption>
          <thead>
            <tr>
              <th scope="col" class="col-client">Service</th>
              <th scope="col">Platforms</th>
              <th scope="col" class="num">Price today</th>
              <th scope="col">Payment</th>
              <th scope="col">Next change</th>
              <th scope="col">GFS code</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            @for (s of shown(); track s.code) {
              <tr>
                <td>
                  <a [routerLink]="['/catalogue', s.code]" class="row-link">{{ s.name }}</a>
                  <span class="sub cn">{{ s.code }} · per {{ s.unit }}</span>
                </td>
                <td>{{ upper(s.platforms) }}</td>
                <td class="num">
                  @if (s.pricingBasis === 'QUOTED') {
                    <span class="muted">By quotation</span>
                  } @else if (s.currentPrice) {
                    {{ s.currentPrice | money }}<span class="sub">version {{ s.currentVersion }}</span>
                  } @else {
                    <span class="alert-figure">No price in force</span>
                  }
                </td>
                <td>{{ mode(s.paymentMode) }}</td>
                <td>
                  @if (s.nextPrice) {
                    {{ s.nextPrice | money }}<span class="sub">from {{ s.nextFrom | eat: false }}</span>
                  }
                  @if (s.pendingChangeId) {
                    <span class="state" data-tone="action"><span class="state-mark" aria-hidden="true"></span>Change awaiting approval</span>
                  }
                </td>
                <td class="cn">{{ s.gfsCode }}</td>
                <td><app-state [value]="s.status" /></td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (shown().length === 0) {
        <p class="empty">No service matches “{{ query() }}”.</p>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class CataloguePage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  protected readonly rows = signal<CatalogueService[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly adding = signal(false);
  protected readonly query = signal('');
  protected q = '';
  protected readonly canPropose = computed(() => this.auth.isOfficer() || this.auth.canApprove());
  protected readonly shown = computed(() => {
    const q = this.query().trim().toLowerCase();
    return (this.rows() ?? []).filter((s) => !q || s.code.toLowerCase().includes(q) || s.name.toLowerCase().includes(q));
  });

  constructor() {
    this.load();
  }

  protected upper(list: string[]): string {
    return list.length ? list.map((p) => p.toUpperCase()).join(', ') : 'None';
  }

  protected mode(m: string | null): string {
    return m === 'EXACT' ? 'Exact amount' : m === 'PARTIAL' ? 'Instalments' : m === 'FULL' ? 'Full or more' : '';
  }

  protected proposed(code: string): void {
    this.adding.set(false);
    this.done.set(`New service ${code} proposed. It appears here once a bill approver approves it.`);
    void this.router.navigate(['/approvals']);
  }

  private load(): void {
    this.api.catalogue().subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
  }
}
