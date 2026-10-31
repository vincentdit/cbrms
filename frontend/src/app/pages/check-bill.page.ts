import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, describeError } from '../core/api.service';
import { PublicBill } from '../core/models';
import { ControlNumberPipe, EatPipe, MoneyPipe } from '../shared/format';
import { StateComponent } from '../shared/state.component';

/**
 * Public page, no sign-in. A client enters a control number; CBRMS texts a one-time code to the phone on the bill.
 * The page never says whether a control number exists, so it cannot be used to discover other clients' bills.
 */
@Component({
  selector: 'app-check-bill',
  imports: [FormsModule, RouterLink, MoneyPipe, ControlNumberPipe, EatPipe, StateComponent],
  template: `
    <div class="signin">
      <section class="signin-panel check-panel" aria-labelledby="check-title" lang="en">
        <img src="tirdo-logo.jpg" alt="TIRDO" width="56" height="70" />
        <h1 id="check-title">Check a TIRDO bill</h1>
        <p class="lede">Angalia bili yako ya TIRDO kwa namba ya malipo (control number).</p>

        @if (bill(); as b) {
          <div class="slip check-slip">
            <span class="slip-label">Control number</span>
            <span class="slip-number">{{ b.controlNumber | controlNumber }}</span>
            <span class="slip-amount">{{ b.amount | money: b.currency }}</span>
            <span class="slip-due">Pay by {{ b.dueDate | eat: false }}</span>
          </div>
          <dl class="check-facts">
            <div><dt>For</dt><dd>{{ b.description }}</dd></div>
            <div><dt>Billed to</dt><dd>{{ b.payer }}</dd></div>
            <div><dt>Paid so far</dt><dd>{{ b.paid | money: b.currency }}</dd></div>
            <div><dt>Still to pay</dt><dd class="strong">{{ b.outstanding | money: b.currency }}</dd></div>
            <div><dt>Status</dt><dd><app-state [value]="b.status" /></dd></div>
          </dl>
          <div class="actions"><button type="button" (click)="reset()">Check another bill</button></div>
        } @else {
          <form (ngSubmit)="step() === 'number' ? sendCode() : verify()">
            <label for="cn">Control number</label>
            <input id="cn" name="cn" inputmode="numeric" autocomplete="off" [(ngModel)]="controlNumber" [readonly]="step() === 'code'" required
                   pattern="[0-9 ]{8,20}" />
            @if (step() === 'code') {
              <p class="notice notice-ok" role="status">{{ message() }}</p>
              <label for="otp">6-digit code from the SMS</label>
              <input id="otp" name="otp" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [(ngModel)]="code" required pattern="[0-9]{6}" />
            }
            @if (error()) {
              <p class="form-error" role="alert">{{ error() }}</p>
            }
            <button type="submit" class="primary" [disabled]="busy()">
              {{ step() === 'number' ? 'Send me a code' : 'Show my bill' }}
            </button>
            @if (step() === 'code') {
              <button type="button" class="link-button" (click)="reset()">Use a different control number</button>
            }
          </form>
        }
        <p class="hint">TIRDO staff: <a routerLink="/sign-in">sign in to the console</a></p>
      </section>
    </div>
  `,
})
export class CheckBillPage {
  private readonly api = inject(ApiService);
  protected readonly step = signal<'number' | 'code'>('number');
  protected readonly bill = signal<PublicBill | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal('');
  protected controlNumber = '';
  protected code = '';

  protected sendCode(): void {
    if (!/^[0-9 ]{8,20}$/.test(this.controlNumber.trim())) {
      this.error.set('Enter the control number as it appears on the bill, digits only.');
      return;
    }
    this.run(this.api.lookupCode(this.controlNumber.trim()), (r: { message: string }) => {
      this.message.set(r.message);
      this.step.set('code');
    });
  }

  protected verify(): void {
    if (!/^[0-9]{6}$/.test(this.code.trim())) {
      this.error.set('Enter the 6-digit code from the SMS.');
      return;
    }
    this.run(this.api.lookupVerify(this.controlNumber.trim(), this.code.trim()), (b: PublicBill) => this.bill.set(b));
  }

  protected reset(): void {
    this.step.set('number');
    this.bill.set(null);
    this.error.set(null);
    this.controlNumber = '';
    this.code = '';
  }

  private run<T>(call: import('rxjs').Observable<T>, ok: (value: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (v) => {
        this.busy.set(false);
        ok(v);
      },
      error: (e) => {
        this.busy.set(false);
        const m = describeError(e);
        this.error.set(m.includes('status 429') ? 'Too many attempts. Wait a few minutes and try again.' : m);
      },
    });
  }
}
