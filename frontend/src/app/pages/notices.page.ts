import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { NoticeRow } from '../core/models';
import { EatPipe, NOTICE_TEMPLATES } from '../shared/format';
import { StateComponent } from '../shared/state.component';

/** The latest SMS and email notices to clients. Recipients are masked; lookup codes are never shown in production. */
@Component({
  selector: 'app-notices',
  imports: [RouterLink, EatPipe, StateComponent],
  template: `
    <header class="page-head">
      <h1>Client messages</h1>
      <p class="lede">
        SMS and email sent to clients in Kiswahili or English: control numbers, payment receipts, cancellations and new
        deadlines. Phone numbers and emails are partly hidden. The latest 100 are shown.
      </p>
    </header>

    @if (error()) {
      <p class="notice notice-alert" role="alert">{{ error() }}</p>
    }

    @if (rows(); as list) {
      @if (list.length === 0) {
        <p class="empty">No messages yet.</p>
      } @else {
        <ul class="notice-list">
          @for (n of list; track n.noticeId) {
            <li>
              <div class="notice-meta">
                <strong>{{ label(n.template) }}</strong>
                <span>{{ n.channel }} to {{ n.recipient }}, {{ n.language === 'sw' ? 'Kiswahili' : 'English' }}</span>
                <span class="sub">{{ n.createdAt | eat }} · <a [routerLink]="['/bills', n.billId]">Open bill</a></span>
              </div>
              <p class="notice-body">{{ n.body }}</p>
              <app-state [value]="n.status" />
            </li>
          }
        </ul>
      }
    } @else if (!error()) {
      <p class="loading">Loading…</p>
    }
  `,
})
export class NoticesPage {
  private readonly api = inject(ApiService);
  protected readonly rows = signal<NoticeRow[] | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.notices().subscribe({ next: (r) => this.rows.set(r), error: (e) => this.error.set(describeError(e)) });
  }

  protected label(t: string): string {
    return NOTICE_TEMPLATES[t] ?? t;
  }
}
