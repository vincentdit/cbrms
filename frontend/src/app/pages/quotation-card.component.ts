import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Quotation } from '../core/models';
import { EatPipe, MoneyPipe } from '../shared/format';
import { PlatformComponent } from '../shared/platform.component';
import { StateComponent } from '../shared/state.component';

/** One quotation: the agreed price, its milestones and which have been billed. */
@Component({
  selector: 'app-quotation-card',
  imports: [RouterLink, MoneyPipe, EatPipe, StateComponent, PlatformComponent],
  template: `
    @let q = quotation();
    <article class="quote">
      <header class="quote-head">
        <div>
          @if (link()) {
            <a [routerLink]="['/quotations', q.quotationId]" class="row-link quote-title">{{ q.payerName }}</a>
          } @else {
            <h2 class="quote-title">{{ q.payerName }}</h2>
          }
          <span class="sub"><app-platform [code]="q.sourceSystem" /> quotation {{ q.quoteReference }} · {{ q.serviceName }}</span>
          <p class="quote-desc">{{ q.description }}</p>
        </div>
        <div class="quote-figures">
          <app-state [value]="q.status" />
          <span class="approval-amount">{{ q.total | money: q.currency }}</span>
          <span class="sub">Billed {{ q.billed | money: q.currency }} · valid until {{ q.validUntil | eat: false }}</span>
        </div>
      </header>
      <div class="table-wrap">
        <table class="data compact">
          <caption class="visually-hidden">Milestones of quotation {{ q.quoteReference }}</caption>
          <thead>
            <tr>
              <th scope="col" class="num">#</th>
              <th scope="col">Milestone</th>
              <th scope="col" class="num">Amount</th>
              <th scope="col">Billing</th>
            </tr>
          </thead>
          <tbody>
            @for (m of q.milestones; track m.milestone) {
              <tr>
                <td class="num">{{ m.milestone }}</td>
                <td>{{ m.description }}</td>
                <td class="num">{{ m.amount | money: q.currency }}</td>
                <td>
                  @if (m.billId) {
                    <span class="milestone-bill">
                      <app-state [value]="m.billState === 'PartiallyPaid' ? 'PartiallyPaid' : m.billState === 'Paid' ? 'Paid' : 'Billed'" />
                      <a [routerLink]="['/bills', m.billId]">Open bill</a>
                    </span>
                  } @else {
                    <span class="muted">Not billed yet</span>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <p class="sub">Submitted by {{ q.requestedBy }} on {{ q.createdAt | eat }}@if (q.decidedAt) {; decided {{ q.decidedAt | eat }}}.</p>
    </article>
  `,
})
export class QuotationCardComponent {
  readonly quotation = input.required<Quotation>();
  readonly link = input(true);
}
