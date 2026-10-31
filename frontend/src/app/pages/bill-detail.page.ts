import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { Activity, Bill, NoticeRow } from '../core/models';
import { CASE_TYPES, ControlNumberPipe, EatPipe, EVENT_TYPES, MoneyPipe, NOTICE_TEMPLATES, stateInfo } from '../shared/format';
import { StateComponent } from '../shared/state.component';
import { BillActionsComponent } from './bill-actions.component';
import { DecisionComponent } from './decision.component';

@Component({
  selector: 'app-bill-detail',
  imports: [RouterLink, MoneyPipe, ControlNumberPipe, EatPipe, StateComponent, DecisionComponent, BillActionsComponent],
  template: `
    <p class="back"><a routerLink="/bills">Back to bills</a></p>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }
    @if (done()) {
      <p class="notice notice-ok" role="status">{{ done() }}</p>
    }

    @if (bill(); as b) {
      <header class="bill-head">
        <div>
          <h1>{{ b.description }}</h1>
          <p class="lede">{{ b.sourceSystem.toUpperCase() }} request {{ b.sourceReference }}@if (b.liabilityVersion > 1) {, version {{ b.liabilityVersion }}}</p>
        </div>
        <div class="slip" [class.slip-pending]="!b.controlNumber">
          <span class="slip-label">Control number</span>
          @if (b.controlNumber) {
            <span class="slip-number">{{ b.controlNumber | controlNumber }}</span>
          } @else {
            <span class="slip-pending-text">Not issued yet</span>
          }
          <span class="slip-amount">{{ b.total | money: b.currency }}</span>
          <span class="slip-due">Pay by {{ b.dueDate | eat: false }}</span>
        </div>
      </header>

      <ol class="lifecycle" aria-label="Bill progress">
        @for (step of steps(); track step.name) {
          <li [attr.data-tone]="step.tone">
            <span class="step-name">{{ step.name }}</span>
            <span class="step-state">{{ step.label }}</span>
          </li>
        }
      </ol>

      @if (b.preparationState === 'AwaitingApproval' && auth.canApprove()) {
        <app-decision [subjectId]="b.billId" kind="BILL" (decided)="reload()" />
      } @else if (b.preparationState === 'AwaitingApproval') {
        <p class="notice">This bill needs approval by a bill approver before it is sent to GePG.</p>
      }
      @if (b.externalRegistrationStatus === 'Cancelled' && b.preparationState !== 'Superseded') {
        <p class="notice">
          GePG has cancelled this bill. {{ b.sourceSystem.toUpperCase() }} can now send a replacement for request
          {{ b.sourceReference }}.
        </p>
      }
      @if (b.preparationState === 'Superseded') {
        <p class="notice">This bill was cancelled and replaced. The replacement is the bill clients should pay.</p>
      }

      @if (activity(); as a) {
        @if (auth.isOfficer() || auth.canApprove()) {
          <app-bill-actions [bill]="b" [activity]="a" (done)="actionDone($event)" />
        }

        @if (a.changes.length > 0) {
          <section class="block" aria-labelledby="changes-title">
            <h2 id="changes-title">Change requests</h2>
            <ul class="case-list">
              @for (c of a.changes; track c.requestId) {
                <li>
                  <app-state [value]="c.status" />
                  <strong>{{ c.kind === 'CANCELLATION' ? 'Cancel bill' : 'New deadline ' + (c.newDueDate | eat: false) }}</strong>
                  <span>{{ c.reason }}. Requested by {{ c.requestedBy }} on {{ c.createdAt | eat }}.</span>
                </li>
              }
            </ul>
          </section>
        }

        @if (a.credits.length > 0) {
          <section class="block" aria-labelledby="credit-title">
            <h2 id="credit-title">Client credit</h2>
            <ul class="case-list">
              @for (c of a.credits; track c.creditId) {
                <li>
                  <app-state [value]="c.status" />
                  <strong>{{ c.amount | money: b.currency }}</strong>
                  @if (c.refundStatus) {
                    <span>Refund: <app-state [value]="c.refundStatus" /> <a routerLink="/refunds">See refunds</a></span>
                  }
                </li>
              }
            </ul>
          </section>
        }
      }

      <section class="block" aria-labelledby="lines-title">
        <h2 id="lines-title">What is charged</h2>
        <div class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th scope="col">Service</th>
                <th scope="col" class="num">Quantity</th>
                <th scope="col" class="num">Unit price</th>
                <th scope="col" class="num">Line total</th>
              </tr>
            </thead>
            <tbody>
              @for (l of b.lines; track l.lineNo) {
                <tr>
                  <td>{{ l.description }}<span class="sub">{{ l.serviceCode }}, tariff version {{ l.tariffVersion }}</span></td>
                  <td class="num">{{ l.quantity }}</td>
                  <td class="num">{{ l.unitPrice | money: b.currency }}</td>
                  <td class="num">{{ l.lineTotal | money: b.currency }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="3">Bill total ({{ modeLabel(b.paymentMode) }})</th>
                <td class="num strong">{{ b.total | money: b.currency }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </section>

      @if (activity(); as a) {
        <section class="block" aria-labelledby="pay-title">
          <h2 id="pay-title">Payments</h2>
          @if (a.payments.length === 0) {
            <p class="empty">No payment received yet.</p>
          } @else {
            <div class="table-wrap">
              <table class="data">
                <thead>
                  <tr>
                    <th scope="col">GePG payment reference</th>
                    <th scope="col">Channel</th>
                    <th scope="col">Paid</th>
                    <th scope="col" class="num">Received</th>
                    <th scope="col" class="num">Applied to bill</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of a.payments; track p.paymentId) {
                    <tr>
                      <td class="cn">{{ p.transactionId }}</td>
                      <td>{{ p.channel }}</td>
                      <td class="nowrap">{{ p.paidAt | eat }}</td>
                      <td class="num">{{ p.amount | money: b.currency }}</td>
                      <td class="num">{{ p.allocated | money: b.currency }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="totals">
              Applied {{ a.allocated | money: b.currency }} of {{ b.total | money: b.currency }}.
              @if (a.credit !== '0' && a.credit !== '0.00') {
                <strong>{{ a.credit | money: b.currency }} held as client credit</strong>, not counted as revenue.
              }
            </p>
          }
        </section>

        @if (a.cases.length > 0) {
          <section class="block" aria-labelledby="cases-title">
            <h2 id="cases-title">Exceptions on this bill</h2>
            <ul class="case-list">
              @for (c of a.cases; track c.caseId) {
                <li>
                  <app-state [value]="c.status" />
                  <strong>{{ caseType(c.type) }}</strong>
                  <span>{{ c.details }}</span>
                </li>
              }
            </ul>
          </section>
        }

        <section class="block" aria-labelledby="history-title">
          <h2 id="history-title">History sent to {{ b.sourceSystem.toUpperCase() }}</h2>
          <ol class="timeline">
            @for (e of a.events; track e.sequence) {
              <li>
                <span class="tl-seq" aria-label="Event {{ e.sequence }}">{{ e.sequence }}</span>
                <span class="tl-what">{{ eventLabel(e.type) }}</span>
                <span class="tl-when">{{ e.createdAt | eat }}</span>
                <app-state [value]="e.deliveryStatus" />
              </li>
            }
          </ol>
        </section>

        <section class="block" aria-labelledby="notices-title">
          <h2 id="notices-title">Messages to the client</h2>
          @if (notices().length === 0) {
            <p class="empty">No SMS or email has been queued for this bill.</p>
          } @else {
            <ol class="timeline">
              @for (n of notices(); track n.noticeId) {
                <li>
                  <span class="tl-seq">{{ n.channel }}</span>
                  <span class="tl-what">{{ noticeLabel(n.template) }} to {{ n.recipient }}</span>
                  <span class="tl-when">{{ n.createdAt | eat }}</span>
                  <app-state [value]="n.status" />
                </li>
              }
            </ol>
          }
        </section>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class BillDetailPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);
  readonly id = input.required<string>();
  protected readonly bill = signal<Bill | null>(null);
  protected readonly activity = signal<Activity | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly notices = signal<NoticeRow[]>([]);

  protected readonly steps = computed(() => {
    const b = this.bill();
    if (!b) {
      return [];
    }
    return [
      { name: 'Prepared', ...stateInfo(b.preparationState) },
      { name: 'Registered with GePG', ...stateInfo(b.externalRegistrationStatus) },
      { name: 'Collected', ...stateInfo(b.collectionState) },
      { name: 'Service', ...stateInfo(b.releaseState) },
    ];
  });

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
  }

  reload(): void {
    this.load(this.id());
  }

  protected actionDone(message: string): void {
    this.done.set(message);
    this.reload();
  }

  protected noticeLabel(t: string): string {
    return NOTICE_TEMPLATES[t] ?? t;
  }

  protected caseType(t: string): string {
    return CASE_TYPES[t] ?? t;
  }

  protected eventLabel(t: string): string {
    return EVENT_TYPES[t] ?? t;
  }

  protected modeLabel(m: string): string {
    return m === 'EXACT' ? 'pay exact amount' : m === 'PARTIAL' ? 'instalments allowed' : 'full or more';
  }

  private load(id: string): void {
    this.error.set(null);
    forkJoin({ bill: this.api.bill(id), activity: this.api.activity(id), notices: this.api.notices(id) }).subscribe({
      next: ({ bill, activity, notices }) => {
        this.bill.set(bill);
        this.activity.set(activity);
        this.notices.set(notices);
      },
      error: (e) => this.error.set(describeError(e)),
    });
  }
}
