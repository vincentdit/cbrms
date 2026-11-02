import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { firstValueFrom } from 'rxjs';
import { Me } from './models';
import { SettingsService } from './settings.service';

const STORAGE_KEY = 'cbrms.basic';

/**
 * Two sign-in modes, chosen by authMode in /config.json:
 *  - "oidc": Keycloak single sign-on (authorization code with PKCE). Tokens stay in memory and are refreshed before use.
 *  - "basic": fixed development users, for a plain local run without Keycloak. The backend refuses this mode
 *    unless the GePG simulator is on.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly settings = inject(SettingsService);
  private readonly credentials = signal<string | null>(this.read());
  private keycloak: Keycloak | null = null;
  readonly me = signal<Me | null>(null);

  readonly signedIn = computed(() => this.me() !== null);
  readonly sso = computed(() => this.settings.settings().authMode === 'oidc');
  readonly canApprove = computed(() => this.has('BILL_APPROVER'));
  readonly isOfficer = computed(() => this.has('BILLING_OFFICER'));
  readonly isFinance = computed(() =>
    (this.me()?.roles ?? []).some((r) => ['BILLING_OFFICER', 'BILL_APPROVER', 'FINANCE_VIEWER', 'AUDITOR'].includes(r)),
  );
  /** Finance viewers and auditors can open every Finance page but change nothing. */
  readonly readOnly = computed(() => this.isFinance() && !this.has('BILLING_OFFICER') && !this.has('BILL_APPROVER'));
  readonly isAuditor = computed(() => this.has('AUDITOR'));

  /** Runs once at start-up, after settings load. In SSO mode it completes a sign-in redirect if there is one. */
  async init(): Promise<void> {
    if (!this.sso()) {
      return;
    }
    const oidc = this.settings.settings().oidc;
    if (!oidc) {
      console.error('authMode is "oidc" but config.json has no "oidc" block');
      return;
    }
    this.keycloak = new Keycloak({ url: oidc.url, realm: oidc.realm, clientId: oidc.clientId });
    // The public bill check needs no staff session, so it never waits on Keycloak.
    if (location.pathname.startsWith('/check')) {
      return;
    }
    await this.startKeycloak();
  }

  private async startKeycloak(): Promise<void> {
    const kc = this.keycloak;
    if (!kc || kc.didInitialize) {
      return;
    }
    try {
      const authenticated = await kc.init({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false });
      kc.onTokenExpired = () => void kc.updateToken(30).catch(() => this.me.set(null));
      if (authenticated) {
        this.me.set(await firstValueFrom(this.http.get<Me>('/internal/v1/me')));
      }
    } catch (e) {
      console.error('Single sign-on is not reachable', e);
    }
  }

  /** The Authorization header value for the next API call, refreshing the SSO token when it is about to expire. */
  async authorization(): Promise<string | null> {
    if (this.keycloak) {
      if (!this.keycloak.didInitialize || !this.keycloak.authenticated) {
        return null;
      }
      try {
        await this.keycloak.updateToken(30);
      } catch {
        return null;
      }
      return this.keycloak.token ? `Bearer ${this.keycloak.token}` : null;
    }
    const c = this.credentials();
    return c ? `Basic ${c}` : null;
  }

  /** SSO: sends the browser to the TIRDO sign-in page, returning to the given console path afterwards. */
  async ssoLogin(returnPath = '/'): Promise<void> {
    if (this.keycloak && !this.keycloak.didInitialize) {
      await this.keycloak.init({ pkceMethod: 'S256', checkLoginIframe: false });
    }
    await this.keycloak?.login({ redirectUri: location.origin + returnPath });
  }

  async login(username: string, password: string): Promise<void> {
    const encoded = btoa(`${username}:${password}`);
    const headers = new HttpHeaders({ Authorization: `Basic ${encoded}` });
    const me = await firstValueFrom(this.http.get<Me>('/internal/v1/me', { headers }));
    this.credentials.set(encoded);
    this.write(encoded);
    this.me.set(me);
  }

  /** Restores a basic-mode session after a page reload. */
  async restore(): Promise<boolean> {
    if (this.sso() || !this.credentials()) {
      return false;
    }
    try {
      this.me.set(await firstValueFrom(this.http.get<Me>('/internal/v1/me')));
      return true;
    } catch {
      this.logout();
      return false;
    }
  }

  /** SSO only: the TIRDO account page, where the password is changed. */
  async manageAccount(): Promise<void> {
    if (this.keycloak?.authenticated) {
      await this.keycloak.accountManagement();
    }
  }

  /** Signs out of the console; in SSO mode also ends the Keycloak session. */
  logout(): void {
    this.me.set(null);
    if (this.keycloak?.authenticated) {
      void this.keycloak.logout({ redirectUri: location.origin + '/sign-in' });
      return;
    }
    this.credentials.set(null);
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable */
    }
  }

  private has(role: string): boolean {
    return this.me()?.roles.includes(role) ?? false;
  }

  private read(): string | null {
    try {
      return sessionStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }

  private write(value: string): void {
    try {
      sessionStorage.setItem(STORAGE_KEY, value);
    } catch {
      /* storage unavailable: session lasts until reload */
    }
  }
}
