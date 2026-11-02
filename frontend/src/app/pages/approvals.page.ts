import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, Decision, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ApprovalKind, ApprovalRow } from '../core/models';
import { APPROVAL_KINDS, EatPipe, MoneyPipe } from '../shared/format';
import { PlatformComponent } from '../shared/platform.component';
import { DecisionComponent } from './decision.component';

@Component({
  selector: 'app-approvals',
  imports: [RouterLink, MoneyPipe, EatPipe, DecisionComponent, PlatformComponent],
  template: `
    <header class="page-head">
      <h1>Approvals</h1>
      <p class="lede">
        Everything that needs a second officer: new bills, consultancy quotations, catalogue and price changes, payment corrections, cancellations, new deadlines and refunds. Oldest first. You cannot
        decide on a request you made yourself.
      </p>
    </header>

    @if (!auth.canApprove()) {
      <p class="notice">You can see this queue, but only a bill approver can approve or reject.</p>
    }
    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (rows(); as list) {
      @if (list.length > 0) {
        <div class="chips" role="group" aria-label="Show">
          @for (k of kinds(); track k.kind) {
            <button type="button" class="chip" [class.on]="filter() === k.kind" [attr.aria-pressed]="filter() === k.kind" (click)="filter.set(k.kind)">
              {{ k.label }} <span class="chip-count">{{ k.count }}</span>
            </button>
          }
        </div>
      }
      @if (shown().length === 0) {
        <p class="all-clear">Nothing is waiting for approval.</p>
      } @else {
        <ul class="approval-list">
          @for (a of shown(); track a.subjectId) {
            <li>
              <div class="approval-facts">
                <span class="kind-tag" [attr.data-kind]="a.kind">{{ kindLabel(a.kind) }}</span>
                @if (a.kind === 'CATALOGUE') {
                  <a [routerLink]="['/catalogue', a.sourceReference]" class="row-link">{{ a.payerName }}</a>
                } @else if (a.billId) {
                  <a [routerLink]="['/bills', a.billId]" class="row-link">{{ a.payerName }}</a>
                } @else if (a.kind === 'QUOTATION') {
                  <a [routerLink]="['/quotations', a.subjectId]" class="row-link">{{ a.payerName }}</a>
                } @else {
                  <a routerLink="/refunds" class="row-link">{{ a.payerName }}</a>
                }
                @if (a.kind !== 'CATALOGUE' || a.total !== '0') {
                  <span class="approval-amount">{{ a.total | money: a.currency }}@if (a.kind === 'CATALOGUE') { <span class="sub">new price per unit</span>}</span>
                }
                <span class="sub">
                  {{ a.description }}. <app-platform [code]="a.sourceSystem" /> {{ a.sourceReference }}, requested by {{ a.maker }} on
                  {{ a.requestedAt | eat }}
                </span>
                @if (a.detail) {
                  <span class="approval-detail">{{ a.detail }}</span>
                }
              </div>
              @if (auth.canApprove()) {
                <app-decision [subjectId]="a.subjectId" [kind]="a.kind" (decided)="decided(a, $event)" />
              }
            </li>
          }
        </ul>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class ApprovalsPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  protected readonly rows = signal<ApprovalRow[] | null>(null);
  protected readonly filter = signal<ApprovalKind | 'ALL'>('ALL');
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);

  protected readonly kinds = computed(() => {
    const list = this.rows() ?? [];
    const all = [{ kind: 'ALL' as const, label: 'All', count: list.length }];
    const by = (['BILL', 'QUOTATION', 'CATALOGUE', 'CORRECTION', 'CANCELLATION', 'EXPIRY_CHANGE', 'REFUND'] as ApprovalKind[])
      .map((kind) => ({ kind, label: APPROVAL_KINDS[kind], count: list.filter((r) => r.kind === kind).length }))
      .filter((k) => k.count > 0);
    return [...all, ...by];
  });

  protected readonly shown = computed(() => {
    const f = this.filter();
    return (this.rows() ?? []).filter((r) => f === 'ALL' || r.kind === f);
  });

  constructor() {
    this.load();
  }

  protected kindLabel(k: string): string {
    return APPROVAL_KINDS[k] ?? k;
  }

  protected decided(a: ApprovalRow, decision: Decision): void {
    const what = this.kindLabel(a.kind).toLowerCase();
    this.done.set(`${decision === 'APPROVED' ? 'Approved' : 'Rejected'}: ${what} for ${a.payerName} (${a.sourceReference}).`);
    this.load();
  }

  private load(): void {
    this.api.approvals().subscribe({
      next: (r) => {
        this.rows.set(r);
        if (this.filter() !== 'ALL' && !r.some((x) => x.kind === this.filter())) {
          this.filter.set('ALL');
        }
      },
      error: (e) => this.error.set(describeError(e)),
    });
  }
}
