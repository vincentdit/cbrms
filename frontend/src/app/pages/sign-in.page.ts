import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { describeError } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { SettingsService } from '../core/settings.service';

@Component({
  selector: 'app-sign-in',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="signin">
      <div class="signin-layout">
      <section class="signin-brand">
        <img src="tirdo-logo.jpg" alt="" width="56" height="70" />
        <p class="signin-kicker">Tanzania Industrial Research and Development Organization</p>
        <p class="brand-title">TIRDO Central Billing and Revenue Management System</p>
        <p class="lede">One record of GePG bills and verified payments for every TIRDO platform. A payment is stored once.</p>
      </section>
      <section class="signin-panel" aria-labelledby="signin-title">
        <h1 id="signin-title">Sign in</h1>
        <p class="lede">Finance console for TIRDO CBRMS</p>
        @if (auth.sso()) {
          <p>Use your TIRDO staff account. You may be asked for a second factor.</p>
          @if (error()) {
            <p class="form-error" role="alert">{{ error() }}</p>
          }
          <button type="button" class="primary" (click)="sso()" [disabled]="busy()">
            {{ busy() ? 'Opening TIRDO sign-in…' : 'Sign in with TIRDO account' }}
          </button>
          @if (settings.settings().simulator) {
            <p class="hint">Development accounts: <code>admin</code> / admin-dev (system administrator), <code>finance.maker</code> / maker-dev, <code>finance.approver</code> / approver-dev and <code>internal.auditor</code> / auditor-dev (read-only).</p>
          }
        } @else {
        <form (ngSubmit)="submit()" #f="ngForm">
          <label for="username">Username</label>
          <input id="username" name="username" [(ngModel)]="username" autocomplete="username" required autofocus />
          <label for="password">Password</label>
          <input id="password" name="password" type="password" [(ngModel)]="password" autocomplete="current-password" required />
          @if (error()) {
            <p class="form-error" role="alert">{{ error() }}</p>
          }
          <button type="submit" class="primary" [disabled]="busy() || f.invalid">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        @if (settings.settings().simulator) {
          <p class="hint">
            Development users: <code>admin</code> / admin-dev (system administrator), <code>finance.maker</code> / maker-dev, <code>finance.approver</code> / approver-dev and <code>internal.auditor</code> / auditor-dev (read-only).
          </p>
        }
        }
        <p class="hint"><a routerLink="/check">Client? Check a bill with its control number</a></p>
      </section>
      </div>
    </div>
  `,
})
export class SignInPage {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly settings = inject(SettingsService);
  protected username = '';
  protected password = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.auth.signedIn()) {
      void this.router.navigate(['/']);
    }
  }

  async sso(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.ssoLogin('/');
    } catch {
      this.error.set('TIRDO sign-in is not reachable. Check that Keycloak is running.');
      this.busy.set(false);
    }
  }

  async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.login(this.username.trim(), this.password);
      await this.router.navigate(['/']);
    } catch (e) {
      const message = describeError(e);
      this.error.set(message.includes('status 401') ? 'Username or password is not correct.' : message);
    } finally {
      this.busy.set(false);
    }
  }
}
