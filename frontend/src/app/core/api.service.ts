import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import {
  Activity,
  ApiError,
  ApprovalKind,
  ApprovalRow,
  Bill,
  BillRow,
  CaseRow,
  CollectionsReport,
  GroupBy,
  OutstandingReport,
  ChangeView,
  NoticeRow,
  Page,
  PlatformRow,
  Quotation,
  PublicBill,
  ReconciliationItem,
  ReconciliationRun,
  RefundRow,
  RunResult,
  Summary,
} from './models';

export type Decision = 'APPROVED' | 'REJECTED';

export interface BillQuery {
  search?: string;
  collection?: string;
  registration?: string;
  preparation?: string;
  source?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  summary(): Observable<Summary> {
    return this.http.get<Summary>('/internal/v1/admin/summary');
  }

  bills(q: BillQuery): Observable<Page<BillRow>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(q)) {
      if (v !== undefined && v !== null && v !== '') {
        params = params.set(k, String(v));
      }
    }
    return this.http.get<Page<BillRow>>('/internal/v1/admin/bills', { params });
  }

  collectionsReport(from: string, to: string, groupBy: GroupBy): Observable<CollectionsReport> {
    return this.http.get<CollectionsReport>('/internal/v1/admin/reports/collections', { params: { from, to, groupBy } });
  }

  outstandingReport(): Observable<OutstandingReport> {
    return this.http.get<OutstandingReport>('/internal/v1/admin/reports/outstanding');
  }

  /**
   * Downloads a CSV through HttpClient (so the sign-in header is sent) and saves it with the server's file name.
   */
  async download(path: string, params: Record<string, string> = {}): Promise<void> {
    const response = await firstValueFrom(this.http.get(path, { params, observe: 'response', responseType: 'blob' }));
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const name = /filename="([^"]+)"/.exec(disposition)?.[1] ?? 'cbrms-report.csv';
    const url = URL.createObjectURL(response.body as Blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  quotations(status = ''): Observable<Quotation[]> {
    return this.http.get<Quotation[]>('/internal/v1/admin/quotations', { params: status ? { status } : {} });
  }

  quotation(id: string): Observable<Quotation> {
    return this.http.get<Quotation>(`/internal/v1/quotations/${id}`);
  }

  platforms(): Observable<PlatformRow[]> {
    return this.http.get<PlatformRow[]>('/internal/v1/admin/platforms');
  }

  bill(id: string): Observable<Bill> {
    return this.http.get<Bill>(`/internal/v1/bills/${id}`);
  }

  activity(id: string): Observable<Activity> {
    return this.http.get<Activity>(`/internal/v1/admin/bills/${id}/activity`);
  }

  approvals(): Observable<ApprovalRow[]> {
    return this.http.get<ApprovalRow[]>('/internal/v1/admin/approvals');
  }

  decide(id: string, decision: Decision, reason: string | null): Observable<Bill> {
    return this.http.post<Bill>(`/internal/v1/bills/${id}/approval`, { decision, reason });
  }

  /** One decision call for every approval kind; the server refuses the maker's own request. */
  decideAny(kind: ApprovalKind, subjectId: string, decision: Decision, reason: string | null): Observable<unknown> {
    const body = { decision, reason };
    switch (kind) {
      case 'BILL':
        return this.http.post(`/internal/v1/bills/${subjectId}/approval`, body);
      case 'REFUND':
        return this.http.post(`/internal/v1/refunds/${subjectId}/decision`, body);
      case 'QUOTATION':
        return this.http.post(`/internal/v1/quotations/${subjectId}/decision`, body);
      default:
        return this.http.post(`/internal/v1/change-requests/${subjectId}/decision`, body);
    }
  }

  requestCancellation(billId: string, reason: string): Observable<ChangeView> {
    return this.http.post<ChangeView>(`/internal/v1/bills/${billId}/cancellation-requests`, { reason });
  }

  requestExpiryChange(billId: string, newDueDate: string, reason: string): Observable<ChangeView> {
    return this.http.post<ChangeView>(`/internal/v1/bills/${billId}/expiry-change-requests`, { newDueDate, reason });
  }

  reconciliationRuns(): Observable<ReconciliationRun[]> {
    return this.http.get<ReconciliationRun[]>('/internal/v1/admin/reconciliation/runs');
  }

  runReconciliation(businessDate: string): Observable<RunResult> {
    return this.http.post<RunResult>('/internal/v1/admin/reconciliation/runs', { businessDate });
  }

  reconciliationItems(batchId: string): Observable<ReconciliationItem[]> {
    return this.http.get<ReconciliationItem[]>(`/internal/v1/admin/reconciliation/runs/${batchId}/items`);
  }

  reviewReconciliation(batchId: string, note: string | null): Observable<unknown> {
    return this.http.post(`/internal/v1/admin/reconciliation/runs/${batchId}/review`, { note });
  }

  refunds(status?: string): Observable<RefundRow[]> {
    return this.http.get<RefundRow[]>('/internal/v1/admin/refunds', { params: status ? { status } : {} });
  }

  requestRefund(creditBalanceId: string, amount: string, reason: string): Observable<unknown> {
    return this.http.post('/internal/v1/admin/refunds', { creditBalanceId, amount, reason });
  }

  recordRefundExecution(refundId: string, executionRef: string | null, failureReason: string | null): Observable<unknown> {
    return this.http.post(`/internal/v1/admin/refunds/${refundId}/execution`, { executionRef, failureReason });
  }

  notices(billId?: string): Observable<NoticeRow[]> {
    return this.http.get<NoticeRow[]>('/internal/v1/admin/notices', { params: billId ? { billId } : {} });
  }

  /** Public, no sign-in: asks CBRMS to text a one-time code to the phone on the bill. */
  lookupCode(controlNumber: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>('/public/v1/bill-lookup/code', { controlNumber: controlNumber.replace(/\s/g, '') });
  }

  lookupVerify(controlNumber: string, code: string): Observable<PublicBill> {
    return this.http.post<PublicBill>('/public/v1/bill-lookup/verify', { controlNumber: controlNumber.replace(/\s/g, ''), code });
  }

  addReconciliationExtra(businessDate: string, transactionId: string, controlNumber: string, amount: string): Observable<unknown> {
    return this.http.post('/sim/v1/reconciliation-extras', { businessDate, transactionId, controlNumber: controlNumber || null, amount });
  }

  cases(status = 'OPEN'): Observable<CaseRow[]> {
    return this.http.get<CaseRow[]>('/internal/v1/admin/cases', { params: { status } });
  }

  simulatePayment(controlNumber: string, amount: string, transactionId?: string): Observable<Record<string, string>> {
    return this.http.post<Record<string, string>>('/sim/v1/payments', {
      controlNumber: controlNumber.replace(/\s/g, ''),
      amount,
      transactionId: transactionId || undefined,
    });
  }

  issueControlNumbers(): Observable<{ issued: number; changesAnswered: number }> {
    return this.http.post<{ issued: number; changesAnswered: number }>('/sim/v1/control-numbers/issue', {});
  }
}

/** Turns an HTTP failure into one sentence the user can act on. */
export function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiError> | null;
    if (error.status === 0) {
      return 'CBRMS is not reachable. Check that the service is running.';
    }
    if (body && typeof body === 'object' && body.message) {
      const fields = body.fieldErrors?.map((f) => `${f.field}: ${f.message}`).join('; ');
      const ref = body.correlationId && body.correlationId !== 'none' ? ` (reference ${body.correlationId})` : '';
      return `${body.message}${fields ? `: ${fields}` : ''}${ref}`;
    }
    if (error.status === 403) {
      return 'Your role does not allow this action.';
    }
    return `Request failed with status ${error.status}.`;
  }
  return 'Something went wrong. Try again.';
}
