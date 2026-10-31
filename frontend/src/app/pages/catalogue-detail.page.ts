import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, Decision, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { CatalogueDetail, CatalogueKind } from '../core/models';
import { EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';
import { CatalogueFormComponent } from './catalogue-form.component';
import { DecisionComponent } from './decision.component';

@Component({
  selector: 'app-catalogue-detail',
  imports: [RouterLink, MoneyPipe, EatPipe, StateComponent, CatalogueFormComponent, DecisionComponent],
  template: `
    <p class="back"><a routerLink="/catalogue">Back to catalogue</a></p>
    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (detail(); as d) {
      @let s = d.service;
      <header class="bill-head">
        <div>
          <h1>{{ s.name }}</h1>
          <p class="lede">
            <span class="cn">{{ s.code }}</span> · {{ s.directorate }} · charged per {{ s.unit }}
            @if (s.nameSw) { <br /><span lang="sw">{{ s.nameSw }}</span> }
          </p>
          <p><app-state [value]="s.status" /> · Platforms: {{ platforms() }}@if (s.requiresApproval) { · each bill needs approval}</p>
        </div>
        <div class="slip" [class.slip-pending]="!s.currentPrice && s.pricingBasis !== 'QUOTED'">
          <span class="slip-label">Price today</span>
          @if (s.pricingBasis === 'QUOTED') {
            <span class="slip-pending-text">By quotation</span>
          } @else if (s.currentPrice) {
            <span class="slip-number">{{ s.currentPrice | money }}</span>
            <span class="slip-due">Version {{ s.currentVersion }} · per {{ s.unit }}</span>
          } @else {
            <span class="slip-pending-text">No price in force</span>
          }
          @if (s.nextPrice) {
            <span class="slip-due">From {{ s.nextFrom | eat: false }}: {{ s.nextPrice | money }}</span>
          }
        </div>
      </header>

      @if (pending(); as c) {
        <section class="block" aria-labelledby="pending-title">
          <h2 id="pending-title">Waiting for approval</h2>
          <p class="notice">
            <strong>{{ c.summary }}</strong><br />
            {{ c.reason }}@if (c.approvalRef) { · Authority: {{ c.approvalRef }}}<br />
            <span class="sub">Proposed by {{ c.requestedBy }} on {{ c.createdAt | eat }}</span>
          </p>
          @if (auth.canApprove()) {
            <app-decision [subjectId]="c.changeId" kind="CATALOGUE" (decided)="decided($event)" />
          }
        </section>
      } @else if (canPropose()) {
        <section class="block bill-actions" aria-labelledby="change-title">
          <h2 id="change-title">Propose a change</h2>
          <div class="actions">
            @if (s.pricingBasis !== 'QUOTED') {
              <button type="button" [class.on]="form() === 'NEW_TARIFF'" (click)="open('NEW_TARIFF')">New price</button>
            } @else {
              <button type="button" [class.on]="form() === 'NEW_TARIFF'" (click)="open('NEW_TARIFF')">New payment rules</button>
            }
            <button type="button" [class.on]="form() === 'NEW_REVENUE_MAPPING'" (click)="open('NEW_REVENUE_MAPPING')">New revenue codes</button>
            <button type="button" [class.on]="form() === 'SERVICE_UPDATE'" (click)="open('SERVICE_UPDATE')">Name, status or platforms</button>
          </div>
          @if (form(); as k) {
            <app-catalogue-form [kind]="k" [current]="s" (saved)="proposed()" (cancel)="form.set(null)" />
          }
        </section>
      }

      <section class="block" aria-labelledby="tariff-title">
        <h2 id="tariff-title">Price history</h2>
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th scope="col" class="num">Version</th>
                <th scope="col" class="num">Price</th>
                <th scope="col">Payment</th>
                <th scope="col">In force</th>
                <th scope="col">Authority</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              @for (t of d.tariffs; track t.version) {
                <tr [class.selected]="t.inEffect">
                  <td class="num">{{ t.version }}</td>
                  <td class="num">{{ s.pricingBasis === 'QUOTED' ? 'By quotation' : (t.unitPrice | money) }}</td>
                  <td>{{ mode(t.paymentMode) }}<span class="sub">{{ t.roundingRule === 'HALF_UP_2' ? 'to the cent' : 'whole shillings' }}, up to {{ t.maxValidityDays }} days</span></td>
                  <td class="nowrap">{{ t.from | eat: false }} – {{ t.to ? (dayBefore(t.to) | eat: false) : 'open' }}@if (t.inEffect) { <span class="sub">in force today</span>}</td>
                  <td>{{ t.approvalRef }}</td>
                  <td><app-state [value]="t.status" /></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="block" aria-labelledby="map-title">
        <h2 id="map-title">Revenue codes</h2>
        <div class="table-wrap">
          <table class="data compact">
            <thead>
              <tr>
                <th scope="col">GFS code</th>
                <th scope="col">Accounting code</th>
                <th scope="col">In force</th>
                <th scope="col">Authority</th>
              </tr>
            </thead>
            <tbody>
              @for (m of d.mappings; track m.from) {
                <tr [class.selected]="m.inEffect">
                  <td class="cn">{{ m.gfsCode }}</td>
                  <td class="cn">{{ m.accountingCode }}</td>
                  <td class="nowrap">{{ m.from | eat: false }} – {{ m.to ? (dayBefore(m.to) | eat: false) : 'open' }}</td>
                  <td>{{ m.approvalRef }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      @if (d.changes.length > 0) {
        <section class="block" aria-labelledby="hist-title">
          <h2 id="hist-title">Change history</h2>
          <ul class="case-list">
            @for (c of d.changes; track c.changeId) {
              <li>
                <app-state [value]="c.status" />
                <strong>{{ c.summary }}</strong>
                <span>{{ c.reason }}. Proposed by {{ c.requestedBy }} on {{ c.createdAt | eat }}@if (c.appliedAt) {; applied {{ c.appliedAt | eat }}}.</span>
              </li>
            }
          </ul>
        </section>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class CatalogueDetailPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  readonly code = input.required<string>();
  protected readonly detail = signal<CatalogueDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly form = signal<CatalogueKind | null>(null);
  protected readonly canPropose = computed(() => this.auth.isOfficer() || this.auth.canApprove());
  protected readonly pending = computed(() => this.detail()?.changes.find((c) => c.status === 'Requested') ?? null);
  protected readonly platforms = computed(() => {
    const list = this.detail()?.service.platforms ?? [];
    return list.length ? list.map((p) => p.toUpperCase()).join(', ') : 'none';
  });

  constructor() {
    effect(() => {
      const code = this.code();
      untracked(() => this.load(code));
    });
  }

  protected open(k: CatalogueKind): void {
    this.form.set(this.form() === k ? null : k);
  }

  protected mode(m: string): string {
    return m === 'EXACT' ? 'Exact amount' : m === 'PARTIAL' ? 'Instalments' : 'Full or more';
  }

  /** Periods are stored with an exclusive end; people read the last day it applies. */
  protected dayBefore(d: string): string {
    const x = new Date(`${d}T00:00:00Z`);
    x.setUTCDate(x.getUTCDate() - 1);
    return x.toISOString().slice(0, 10);
  }

  protected proposed(): void {
    this.form.set(null);
    this.done.set('Change proposed. A bill approver must approve it before it takes effect.');
    this.load(this.code());
  }

  protected decided(d: Decision): void {
    this.done.set(d === 'APPROVED' ? 'Change approved and applied.' : 'Change rejected; nothing was changed.');
    this.load(this.code());
  }

  private load(code: string): void {
    this.api.catalogueService(code).subscribe({ next: (d) => this.detail.set(d), error: (e) => this.error.set(describeError(e)) });
  }
}
