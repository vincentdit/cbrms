import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'check', loadComponent: () => import('./pages/check-bill.page').then((m) => m.CheckBillPage), title: 'Check a bill | TIRDO' },
  { path: 'sign-in', loadComponent: () => import('./pages/sign-in.page').then((m) => m.SignInPage), title: 'Sign in | CBRMS' },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', loadComponent: () => import('./pages/dashboard.page').then((m) => m.DashboardPage), title: 'Today | CBRMS' },
      { path: 'bills', loadComponent: () => import('./pages/bills.page').then((m) => m.BillsPage), title: 'Bills | CBRMS' },
      { path: 'bills/:id', loadComponent: () => import('./pages/bill-detail.page').then((m) => m.BillDetailPage), title: 'Bill | CBRMS' },
      { path: 'approvals', loadComponent: () => import('./pages/approvals.page').then((m) => m.ApprovalsPage), title: 'Approvals | CBRMS' },
      { path: 'reconciliation', loadComponent: () => import('./pages/reconciliation.page').then((m) => m.ReconciliationPage), title: 'Reconciliation | CBRMS' },
      { path: 'refunds', loadComponent: () => import('./pages/refunds.page').then((m) => m.RefundsPage), title: 'Refunds | CBRMS' },
      { path: 'notices', loadComponent: () => import('./pages/notices.page').then((m) => m.NoticesPage), title: 'Client messages | CBRMS' },
      { path: 'cases', loadComponent: () => import('./pages/cases.page').then((m) => m.CasesPage), title: 'Exceptions | CBRMS' },
      { path: 'simulator', loadComponent: () => import('./pages/simulator.page').then((m) => m.SimulatorPage), title: 'GePG simulator | CBRMS' },
    ],
  },
  { path: '**', redirectTo: '' },
];
