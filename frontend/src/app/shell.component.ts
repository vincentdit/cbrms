import { Component, inject } from '@angular/core';
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
        <a routerLink="/" class="brand" aria-label="CBRMS home">
          <img src="tirdo-logo.jpg" alt="" width="34" height="43" />
          <span class="brand-text">
            <strong>CBRMS</strong>
            <span>TIRDO billing and revenue</span>
          </span>
        </a>
        <nav aria-label="Main">
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Today</a>
          <a routerLink="/bills" routerLinkActive="active">Bills</a>
          <a routerLink="/approvals" routerLinkActive="active">Approvals</a>
          <a routerLink="/quotations" routerLinkActive="active">Quotations</a>
          <a routerLink="/cases" routerLinkActive="active">Exceptions</a>
          <a routerLink="/reconciliation" routerLinkActive="active">Reconciliation</a>
          <a routerLink="/refunds" routerLinkActive="active">Refunds</a>
          <a routerLink="/reports" routerLinkActive="active">Reports</a>
          <a routerLink="/notices" routerLinkActive="active">Client messages</a>
          @if (settings.settings().simulator) {
            <a routerLink="/simulator" routerLinkActive="active" class="nav-test">GePG simulator</a>
          }
        </nav>
        <div class="rail-foot">
          @if (settings.settings().environmentLabel) {
            <p class="env">{{ settings.settings().environmentLabel }}</p>
          }
          <p class="who">{{ auth.me()?.displayName }}</p>
          <button type="button" class="link-button" (click)="signOut()">Sign out</button>
        </div>
      </header>
      <main id="main" tabindex="-1">
        @if (auth.me() && !auth.isFinance()) {
          <p class="notice notice-alert">
            You are signed in as a platform client. The console is for Finance staff; most pages will refuse access.
          </p>
        }
        <router-outlet />
      </main>
    </div>
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly settings = inject(SettingsService);
  private readonly router = inject(Router);

  signOut(): void {
    this.auth.logout();
    this.router.navigate(['/sign-in']);
  }
}
