import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, describeError } from '../core/api.service';
import { SettingsService } from '../core/settings.service';

const RESULTS: Record<string, string> = {
  ACCEPTED: 'Payment recorded and applied to the bill.',
  DUPLICATE: 'Already received. CBRMS ignored the repeat, as it should.',
  SUSPENSE: 'No bill has this control number. The payment went to suspense and an exception was opened.',
  REJECTED_SIGNATURE: 'Signature check failed. No money was recorded.',
  IGNORED: 'Message ignored.',
};

/** Acts as GePG in local and test environments only. */
@Component({
  selector: 'app-simulator',
  imports: [FormsModule],
  template: `
    <header class="page-head">
      <h1>GePG simulator</h1>
      <p class="lede">Test environment only. Acts as GePG: pays a control number through the same signed path real payments use.</p>
    </header>

    @if (!settings.settings().simulator) {
      <p class="notice notice-alert">The simulator is switched off in this environment.</p>
    } @else {
      <section class="block sim">
        <h2>Pay a control number</h2>
        <form (ngSubmit)="pay()" #f="ngForm" class="sim-form">
          <div class="field">
            <label for="cn">Control number</label>
            <input id="cn" name="cn" [(ngModel)]="controlNumber" required inputmode="numeric" placeholder="9912 3456 7890" />
          </div>
          <div class="field">
            <label for="amount">Amount (TZS)</label>
            <input id="amount" name="amount" [(ngModel)]="amount" required inputmode="decimal" placeholder="90000.00" />
          </div>
          <div class="field">
            <label for="tx">GePG payment reference <span class="optional">(reuse one to test duplicates)</span></label>
            <input id="tx" name="tx" [(ngModel)]="transactionId" placeholder="Generated if empty" />
          </div>
          <button type="submit" class="primary" [disabled]="busy() || f.invalid">Send payment</button>
        </form>
        @if (result()) {
          <p class="notice" [class.notice-ok]="ok()" [class.notice-alert]="!ok()" role="status">{{ result() }}</p>
        }
      </section>

      <section class="block sim">
        <h2>Control numbers, cancellations and new deadlines</h2>
        <p>The simulator answers every few seconds. Answer everything that is waiting now:</p>
        <button type="button" (click)="issue()" [disabled]="busy()">Answer waiting requests</button>
      </section>

      <section class="block sim">
        <h2>Add a line to the GePG reconciliation file</h2>
        <p>Adds a collection that CBRMS has never seen, so the next reconciliation run shows a difference.</p>
        <form (ngSubmit)="extra()" #x="ngForm" class="sim-form">
          <div class="field">
            <label for="x-day">Business day</label>
            <input id="x-day" name="xday" type="date" [(ngModel)]="extraDay" required />
          </div>
          <div class="field">
            <label for="x-tx">GePG payment reference</label>
            <input id="x-tx" name="xtx" [(ngModel)]="extraTx" required />
          </div>
          <div class="field">
            <label for="x-cn">Control number <span class="optional">(optional)</span></label>
            <input id="x-cn" name="xcn" [(ngModel)]="extraCn" inputmode="numeric" />
          </div>
          <div class="field">
            <label for="x-amount">Amount (TZS)</label>
            <input id="x-amount" name="xamount" [(ngModel)]="extraAmount" required inputmode="decimal" />
          </div>
          <button type="submit" [disabled]="busy() || x.invalid">Add line</button>
        </form>
      </section>
    }
  `,
})
export class SimulatorPage {
  private readonly api = inject(ApiService);
  protected readonly settings = inject(SettingsService);
  protected controlNumber = '';
  protected amount = '';
  protected transactionId = '';
  protected extraDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());
  protected extraTx = `GHOST-${Math.floor(Math.random() * 1e6)}`;
  protected extraCn = '';
  protected extraAmount = '1000.00';
  protected readonly busy = signal(false);
  protected readonly result = signal<string | null>(null);
  protected readonly ok = signal(true);

  pay(): void {
    this.busy.set(true);
    this.api.simulatePayment(this.controlNumber, this.amount.replace(/,/g, ''), this.transactionId.trim()).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.ok.set(r['result'] === 'ACCEPTED' || r['result'] === 'DUPLICATE');
        this.result.set(`${RESULTS[r['result']] ?? r['result']} Reference ${r['transactionId']}.`);
      },
      error: (e) => {
        this.busy.set(false);
        this.ok.set(false);
        this.result.set(describeError(e));
      },
    });
  }

  extra(): void {
    this.busy.set(true);
    this.api.addReconciliationExtra(this.extraDay, this.extraTx.trim(), this.extraCn.replace(/\s/g, ''), this.extraAmount.replace(/,/g, '')).subscribe({
      next: () => {
        this.busy.set(false);
        this.ok.set(true);
        this.result.set(`Added ${this.extraTx} to the GePG file for ${this.extraDay}. Run reconciliation for that day to see it.`);
        this.extraTx = `GHOST-${Math.floor(Math.random() * 1e6)}`;
      },
      error: (e) => {
        this.busy.set(false);
        this.ok.set(false);
        this.result.set(describeError(e));
      },
    });
  }

  issue(): void {
    this.busy.set(true);
    this.api.issueControlNumbers().subscribe({
      next: (r) => {
        this.busy.set(false);
        this.ok.set(true);
        const total = r.issued + (r.changesAnswered ?? 0);
        this.result.set(
          total === 0
            ? 'Nothing was waiting for GePG.'
            : `Issued ${r.issued} control ${r.issued === 1 ? 'number' : 'numbers'} and answered ${r.changesAnswered ?? 0} change ${r.changesAnswered === 1 ? 'request' : 'requests'}.`,
        );
      },
      error: (e) => {
        this.busy.set(false);
        this.ok.set(false);
        this.result.set(describeError(e));
      },
    });
  }
}
