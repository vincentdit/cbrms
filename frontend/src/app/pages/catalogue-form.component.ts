import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MoneyPipe } from '../shared/format';
import { ApiService, describeError } from '../core/api.service';
import { CatalogueKind, CatalogueProposal, CatalogueService, Directorate, PlatformRow } from '../core/models';

const TODAY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(new Date());

const TITLES: Record<CatalogueKind, string> = {
  NEW_SERVICE: 'Propose a new service',
  NEW_TARIFF: 'Propose a new price',
  NEW_REVENUE_MAPPING: 'Propose new revenue codes',
  SERVICE_UPDATE: 'Propose a settings change',
};

/** One form for every kind of catalogue proposal. Nothing changes until a second officer approves. */
@Component({
  selector: 'app-catalogue-form',
  imports: [FormsModule, MoneyPipe],
  template: `
    <form class="action-form catalogue-form" (ngSubmit)="submit()" #f="ngForm">
      <h2>{{ title() }}</h2>

      @if (kind() === 'NEW_SERVICE') {
        <div class="form-grid">
          <div>
            <label for="cf-code">Service code</label>
            <input id="cf-code" name="code" [(ngModel)]="p.serviceCode" required pattern="[A-Za-z0-9][A-Za-z0-9-]{2,49}"
                   placeholder="LIMS-PHYS-003" autocomplete="off" />
          </div>
          <div>
            <label for="cf-dir">Directorate</label>
            <select id="cf-dir" name="dir" [(ngModel)]="p.directorateCode" required>
              @for (d of directorates(); track d.code) {
                <option [value]="d.code">{{ d.name }}</option>
              }
            </select>
          </div>
          <div class="span-2">
            <label for="cf-name">Name (English, as printed on bills)</label>
            <input id="cf-name" name="name" [(ngModel)]="p.name" required maxlength="200" />
          </div>
          <div class="span-2">
            <label for="cf-namesw">Name (Kiswahili)</label>
            <input id="cf-namesw" name="nameSw" [(ngModel)]="p.nameSw" maxlength="200" />
          </div>
          <div>
            <label for="cf-unit">Charged per</label>
            <input id="cf-unit" name="unit" [(ngModel)]="p.unit" required maxlength="30" placeholder="sample, day, participant" />
          </div>
          <div>
            <label for="cf-basis">Priced by</label>
            <select id="cf-basis" name="basis" [(ngModel)]="p.pricingBasis">
              <option value="TARIFF">Fixed tariff</option>
              <option value="QUOTED">Quotation per engagement</option>
            </select>
          </div>
        </div>
        <fieldset class="radio-row">
          <legend>Platforms allowed to bill it</legend>
          @for (pl of platforms(); track pl.code) {
            <label><input type="checkbox" [name]="'pl-' + pl.code" [ngModel]="chosen().includes(pl.code)" (ngModelChange)="toggle(pl.code, $event)" />
              {{ pl.code.toUpperCase() }}</label>
          }
        </fieldset>
        <label class="check"><input type="checkbox" name="ra" [(ngModel)]="p.requiresApproval" /> Each bill needs a second officer's approval</label>
      }

      @if (kind() === 'NEW_SERVICE' || kind() === 'NEW_TARIFF') {
        <div class="form-grid">
          @if (p.pricingBasis !== 'QUOTED') {
            <div>
              <label for="cf-price">Price per unit (TZS)</label>
              <input id="cf-price" name="price" [(ngModel)]="p.unitPrice" required inputmode="decimal" pattern="[0-9]+(\\.[0-9]{1,2})?" />
              @if (current()?.currentPrice; as cp) {
                <span class="hint">Today: {{ cp | money }}</span>
              }
            </div>
          }
          <div>
            <label for="cf-mode">Payment</label>
            <select id="cf-mode" name="mode" [(ngModel)]="p.paymentMode">
              <option value="EXACT">Exact amount only</option>
              <option value="PARTIAL">Instalments allowed</option>
              <option value="FULL">Full amount or more</option>
            </select>
          </div>
          <div>
            <label for="cf-round">Rounding</label>
            <select id="cf-round" name="round" [(ngModel)]="p.roundingRule">
              <option value="WHOLE_SHILLING_HALF_UP">Whole shillings</option>
              <option value="HALF_UP_2">To the cent</option>
            </select>
          </div>
          <div>
            <label for="cf-valid">Longest bill validity (days)</label>
            <input id="cf-valid" name="valid" type="number" min="1" max="365" [(ngModel)]="p.maxValidityDays" required />
          </div>
        </div>
      }

      @if (kind() === 'NEW_SERVICE' || kind() === 'NEW_REVENUE_MAPPING') {
        <div class="form-grid">
          <div>
            <label for="cf-gfs">GFS revenue code</label>
            <input id="cf-gfs" name="gfs" [(ngModel)]="p.gfsCode" required maxlength="30" />
          </div>
          <div>
            <label for="cf-acc">Accounting code</label>
            <input id="cf-acc" name="acc" [(ngModel)]="p.accountingCode" required maxlength="30" />
          </div>
        </div>
      }

      @if (kind() !== 'SERVICE_UPDATE') {
        <div class="form-grid">
          <div>
            <label for="cf-from">Takes effect on</label>
            <input id="cf-from" name="from" type="date" [min]="today" [(ngModel)]="p.effectiveFrom" required />
          </div>
          <div>
            <label for="cf-to">Ends on <span class="optional">(optional)</span></label>
            <input id="cf-to" name="to" type="date" [min]="p.effectiveFrom ?? today" [(ngModel)]="p.effectiveTo" />
          </div>
          <div class="span-2">
            <label for="cf-ref">Finance authority</label>
            <input id="cf-ref" name="ref" [(ngModel)]="p.approvalRef" required maxlength="100" placeholder="For example DG approval ref. TIRDO/FIN/2026/045" />
          </div>
        </div>
      }

      @if (kind() === 'SERVICE_UPDATE') {
        <div class="form-grid">
          <div class="span-2">
            <label for="cu-name">Name (English)</label>
            <input id="cu-name" name="uname" [(ngModel)]="p.name" maxlength="200" />
          </div>
          <div class="span-2">
            <label for="cu-namesw">Name (Kiswahili)</label>
            <input id="cu-namesw" name="unamesw" [(ngModel)]="p.nameSw" maxlength="200" />
          </div>
          <div>
            <label for="cu-status">Status</label>
            <select id="cu-status" name="ustatus" [(ngModel)]="p.serviceStatus">
              <option value="ACTIVE">Active: can be billed</option>
              <option value="INACTIVE">Inactive: no new bills</option>
            </select>
          </div>
        </div>
        <fieldset class="radio-row">
          <legend>Platforms allowed to bill it</legend>
          @for (pl of platforms(); track pl.code) {
            <label><input type="checkbox" [name]="'upl-' + pl.code" [ngModel]="chosen().includes(pl.code)" (ngModelChange)="toggle(pl.code, $event)" />
              {{ pl.code.toUpperCase() }}</label>
          }
        </fieldset>
        <label class="check"><input type="checkbox" name="ura" [(ngModel)]="p.requiresApproval" /> Each bill needs a second officer's approval</label>
      }

      <label for="cf-reason">Why is this change needed?</label>
      <textarea id="cf-reason" name="reason" rows="2" maxlength="500" [(ngModel)]="p.reason" required></textarea>

      @if (error()) {
        <p class="form-error" role="alert">{{ error() }}</p>
      }
      <div class="actions">
        <button type="submit" class="primary" [disabled]="busy() || f.invalid">Send for approval</button>
        <button type="button" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>
  `,
})
export class CatalogueFormComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly kind = input.required<CatalogueKind>();
  /** The service being changed (all kinds except NEW_SERVICE). */
  readonly current = input<CatalogueService | null>(null);
  readonly saved = output<string>();
  readonly cancel = output<void>();

  protected readonly today = TODAY;
  protected readonly directorates = signal<Directorate[]>([]);
  protected readonly platforms = signal<PlatformRow[]>([]);
  protected readonly chosen = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected p: CatalogueProposal = { kind: 'NEW_SERVICE', serviceCode: '', reason: '' };

  protected title(): string {
    return TITLES[this.kind()];
  }

  ngOnInit(): void {
    const c = this.current();
    this.p = {
      kind: this.kind(),
      serviceCode: c?.code ?? '',
      reason: '',
      pricingBasis: c?.pricingBasis ?? 'TARIFF',
      paymentMode: c?.paymentMode ?? 'EXACT',
      roundingRule: 'WHOLE_SHILLING_HALF_UP',
      maxValidityDays: 30,
      effectiveFrom: TODAY,
      requiresApproval: c?.requiresApproval ?? false,
      name: this.kind() === 'SERVICE_UPDATE' ? c?.name : null,
      nameSw: this.kind() === 'SERVICE_UPDATE' ? c?.nameSw : null,
      serviceStatus: this.kind() === 'SERVICE_UPDATE' ? c?.status : null,
    };
    this.chosen.set(c?.platforms ?? []);
    if (this.kind() === 'NEW_SERVICE' || this.kind() === 'SERVICE_UPDATE') {
      this.api.platforms().subscribe({ next: (r) => this.platforms.set(r) });
    }
    if (this.kind() === 'NEW_SERVICE') {
      this.api.directorates().subscribe({
        next: (d) => {
          this.directorates.set(d);
          this.p.directorateCode = d[0]?.code ?? null;
        },
      });
    }
  }

  protected toggle(code: string, on: boolean): void {
    this.chosen.set(on ? [...this.chosen(), code] : this.chosen().filter((c) => c !== code));
  }

  protected submit(): void {
    const k = this.kind();
    const body: CatalogueProposal = { ...this.p, kind: k, serviceCode: (this.p.serviceCode ?? '').trim().toUpperCase() };
    if (k === 'NEW_SERVICE' || k === 'SERVICE_UPDATE') {
      body.platforms = this.chosen();
    }
    if (k === 'SERVICE_UPDATE') {
      // send only what actually changes
      const c = this.current();
      body.name = body.name !== c?.name ? body.name : null;
      body.nameSw = body.nameSw !== c?.nameSw ? body.nameSw : null;
      body.serviceStatus = body.serviceStatus !== c?.status ? body.serviceStatus : null;
      body.requiresApproval = body.requiresApproval !== c?.requiresApproval ? body.requiresApproval : null;
      const same = [...(c?.platforms ?? [])].sort().join() === [...this.chosen()].sort().join();
      body.platforms = same ? null : this.chosen();
      body.effectiveFrom = null;
      body.paymentMode = null;
      body.roundingRule = null;
      body.maxValidityDays = null;
    }
    if (body.effectiveTo === '') {
      body.effectiveTo = null;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.proposeCatalogueChange(body).subscribe({
      next: () => {
        this.busy.set(false);
        this.saved.emit(body.serviceCode);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(describeError(e));
      },
    });
  }
}
