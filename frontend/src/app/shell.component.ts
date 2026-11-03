import { Component, ElementRef, HostListener, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { SettingsService } from './core/settings.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <div class="shell">
      <header class="rail">
        <a routerLink="/" class="brand" aria-label="TIRDO CBRMS home">
          <img src="tirdo-logo.jpg" alt="" width="34" height="43" />
          <span class="brand-text">
            <strong>TIRDO CBRMS</strong>
            <span>Central billing and revenue</span>
          </span>
        </a>
        <nav aria-label="Main">
          <p class="nav-label">Overview</p>
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Today</a>
          <p class="nav-label">Billing</p>
          <a routerLink="/bills" routerLinkActive="active">Bills</a>
          <a routerLink="/approvals" routerLinkActive="active">Approvals</a>
          <a routerLink="/quotations" routerLinkActive="active">Quotations</a>
          <a routerLink="/catalogue" routerLinkActive="active">Catalogue</a>
          <p class="nav-label">Revenue</p>
          <a routerLink="/reports" routerLinkActive="active">Reports</a>
          <a routerLink="/reconciliation" routerLinkActive="active">Reconciliation</a>
          <a routerLink="/refunds" routerLinkActive="active">Refunds</a>
          <a routerLink="/cases" routerLinkActive="active">Exceptions</a>
          <p class="nav-label">Control</p>
          <a routerLink="/notices" routerLinkActive="active">Client messages</a>
          <a routerLink="/audit" routerLinkActive="active">Audit trail</a>
          <a routerLink="/operations" routerLinkActive="active">Operations</a>
          @if (settings.settings().simulator && !auth.readOnly()) {
            <p class="nav-label">Test</p>
            <a routerLink="/simulator" routerLinkActive="active" class="nav-test">GePG simulator</a>
          }
        </nav>
      </header>
      <div class="workspace">
        <div class="topbar">
          <div>
            <p class="system-name">TIRDO Central Billing and Revenue Management System</p>
            <p class="system-meta">GePG bills and verified payments for LIMS, CIAP, TeLTP and ISICMS</p>
          </div>
          <div class="account" #accountRoot>
            <button type="button" class="account-button" [attr.aria-expanded]="accountOpen()" aria-haspopup="menu" aria-controls="account-menu"
                    [attr.aria-label]="'Account, ' + (auth.me()?.displayName ?? 'signed in')" (click)="toggleAccount($event)">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="12" cy="8" r="3.2" />
                <path d="M5.2 19.2c.8-3.2 3.3-4.8 6.8-4.8s6 1.6 6.8 4.8" />
              </svg>
            </button>
            @if (accountOpen()) {
              <div id="account-menu" class="account-panel" role="menu">
                <p class="who">{{ auth.me()?.displayName }}</p>
                @if (settings.settings().environmentLabel) {
                  <p class="env-pill">{{ settings.settings().environmentLabel }}</p>
                }
                <button type="button" role="menuitem" (click)="changePassword()">Change password</button>
                <button type="button" role="menuitem" (click)="signOut()">Sign out</button>
              </div>
            }
          </div>
        </div>
        <dialog #passwordDialog class="account-dialog" aria-labelledby="password-title">
          <h2 id="password-title">Change password</h2>
          <p>This local sign-in uses a fixed development password. It is not changed from the console.</p>
          <form method="dialog">
            <button type="submit" class="primary">Close</button>
          </form>
        </dialog>
      <main id="main" tabindex="-1">
        @if (auth.readOnly()) {
          <p class="notice read-only-banner">
            @if (auth.isAdministrator()) {
              System administrator: you can see the whole system. Bills, approvals and payments stay with Finance.
            } @else {
              {{ auth.isAuditor() ? 'Auditor access' : 'Read-only access' }}: you can see everything here, but you cannot approve, request or change anything.
            }
          </p>
        }
        @if (auth.me() && !auth.isFinance()) {
          <p class="notice notice-alert">
            You are signed in as a platform client. The console is for Finance staff; most pages will refuse access.
          </p>
        }
        <router-outlet />
      </main>
      </div>
    </div>
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly settings = inject(SettingsService);
  private readonly router = inject(Router);
  private readonly passwordDialog = viewChild<ElementRef<HTMLDialogElement>>('passwordDialog');
  private readonly accountRoot = viewChild<ElementRef<HTMLElement>>('accountRoot');
  protected readonly accountOpen = signal(false);

  toggleAccount(event: MouseEvent): void {
    event.stopPropagation();
    this.accountOpen.update((open) => !open);
  }

  @HostListener('document:click', ['$event'])
  closeAccountOnOutsideClick(event: MouseEvent): void {
    const root = this.accountRoot()?.nativeElement;
    if (this.accountOpen() && root && !root.contains(event.target as Node)) {
      this.accountOpen.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  closeAccountOnEscape(): void {
    this.accountOpen.set(false);
  }

  changePassword(): void {
    this.accountOpen.set(false);
    if (this.auth.sso()) {
      void this.auth.manageAccount();
      return;
    }
    this.passwordDialog()?.nativeElement.showModal();
  }

  signOut(): void {
    this.accountOpen.set(false);
    this.auth.logout();
    this.router.navigate(['/sign-in']);
  }
}
