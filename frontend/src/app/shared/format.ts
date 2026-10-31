import { Pipe, PipeTransform } from '@angular/core';

const amountFormat = new Intl.NumberFormat('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** "90000.00" -> "TZS 90,000.00". Amounts arrive as decimal strings and are only formatted, never calculated. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: string | null | undefined, currency = 'TZS'): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    const n = Number(value);
    return Number.isFinite(n) ? `${currency} ${amountFormat.format(n)}` : `${currency} ${value}`;
  }
}

/** "991234567890" -> "9912 3456 7890", the way payers read it from a slip. */
@Pipe({ name: 'controlNumber' })
export class ControlNumberPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    return value.replace(/\s/g, '').replace(/(\d{4})(?=\d)/g, '$1 ');
  }
}

const dateTime = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Dar_es_Salaam',
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const dateOnly = new Intl.DateTimeFormat('en-GB', { timeZone: 'Africa/Dar_es_Salaam', day: '2-digit', month: 'short', year: 'numeric' });

/** Dates are shown in Dar es Salaam time, the operational time zone (BRD section 6). */
@Pipe({ name: 'eat' })
export class EatPipe implements PipeTransform {
  transform(value: string | null | undefined, withTime = true): string {
    if (!value || value === 'null') {
      return '';
    }
    const d = new Date(value.length === 10 ? `${value}T00:00:00+03:00` : value);
    return Number.isNaN(d.getTime()) ? value : (withTime ? dateTime : dateOnly).format(d);
  }
}

export type Tone = 'ok' | 'action' | 'alert' | 'quiet' | 'progress';

interface StateInfo {
  label: string;
  tone: Tone;
}

/** Plain-language names for bill states, with the tone that tells Finance whether to act. */
export const STATES: Record<string, StateInfo> = {
  Draft: { label: 'Draft', tone: 'quiet' },
  AwaitingApproval: { label: 'Awaiting approval', tone: 'action' },
  Approved: { label: 'Approved', tone: 'ok' },
  Rejected: { label: 'Rejected', tone: 'alert' },
  Superseded: { label: 'Replaced', tone: 'quiet' },
  NotSubmitted: { label: 'Not sent to GePG', tone: 'quiet' },
  Submitted: { label: 'Sent to GePG', tone: 'progress' },
  AckUnknown: { label: 'GePG outcome unknown', tone: 'alert' },
  AwaitingControlNumber: { label: 'Waiting for control number', tone: 'progress' },
  Accepted: { label: 'Control number issued', tone: 'ok' },
  CancellationPending: { label: 'Cancellation pending', tone: 'progress' },
  Cancelled: { label: 'Cancelled', tone: 'quiet' },
  ExpiryChangePending: { label: 'Expiry change pending', tone: 'progress' },
  Unpaid: { label: 'Unpaid', tone: 'quiet' },
  PartiallyPaid: { label: 'Partly paid', tone: 'progress' },
  Paid: { label: 'Paid', tone: 'ok' },
  Overpaid: { label: 'Overpaid', tone: 'action' },
  Pending: { label: 'Not yet eligible', tone: 'quiet' },
  Eligible: { label: 'Service can be released', tone: 'ok' },
  Released: { label: 'Service released', tone: 'ok' },
  Held: { label: 'Service held', tone: 'alert' },
  OPEN: { label: 'Open', tone: 'alert' },
  IN_PROGRESS: { label: 'In progress', tone: 'action' },
  CLOSED: { label: 'Closed', tone: 'quiet' },
  DELIVERED: { label: 'Delivered', tone: 'ok' },
  PENDING: { label: 'Waiting to deliver', tone: 'progress' },
  FAILED: { label: 'Delivery failed', tone: 'alert' },
  NO_SUBSCRIBER: { label: 'No subscriber', tone: 'quiet' },
  // change requests
  Requested: { label: 'Waiting for approval', tone: 'action' },
  SentToGepg: { label: 'Sent to GePG', tone: 'progress' },
  OutcomeUnknown: { label: 'GePG outcome unknown', tone: 'alert' },
  Confirmed: { label: 'Confirmed by GePG', tone: 'ok' },
  RefusedByGepg: { label: 'Refused by GePG', tone: 'alert' },
  // refunds and credits
  Reviewed: { label: 'Reviewed', tone: 'progress' },
  Executed: { label: 'Paid out', tone: 'ok' },
  ExecutionFailed: { label: 'Payout failed', tone: 'alert' },
  AVAILABLE: { label: 'Available', tone: 'action' },
  APPLIED: { label: 'Applied', tone: 'quiet' },
  REFUNDED: { label: 'Refunded', tone: 'quiet' },
  // reconciliation
  IMPORTED: { label: 'Imported', tone: 'progress' },
  MATCHED: { label: 'Matched', tone: 'ok' },
  REVIEWED: { label: 'Signed off', tone: 'quiet' },
  EXCEPTION: { label: 'Difference', tone: 'alert' },
  UNMATCHED: { label: 'Not matched', tone: 'alert' },
  // notices
  SENT: { label: 'Sent', tone: 'ok' },
  // quotations and milestones
  Withdrawn: { label: 'Withdrawn', tone: 'quiet' },
  Billed: { label: 'Billed', tone: 'progress' },
};

export const RUN_REVIEW: Record<string, StateInfo> = {
  MATCHED: { label: 'Ready to sign off', tone: 'action' },
};

/** Plain names for client notice templates. */
export const NOTICE_TEMPLATES: Record<string, string> = {
  CONTROL_NUMBER_ISSUED: 'Control number issued',
  PAYMENT_RECEIVED: 'Payment received',
  BILL_CANCELLED: 'Bill cancelled',
  EXPIRY_CHANGED: 'New payment deadline',
  LOOKUP_CODE: 'Bill lookup code',
};

export const APPROVAL_KINDS: Record<string, string> = {
  BILL: 'New bill',
  CANCELLATION: 'Cancel bill',
  EXPIRY_CHANGE: 'New deadline',
  REFUND: 'Refund',
  QUOTATION: 'Quotation',
};

export const CASE_TYPES: Record<string, string> = {
  UNMATCHED_PAYMENT: 'Payment with no matching bill',
  AMOUNT_MISMATCH: 'Amount differs from bill',
  LATE_PAYMENT: 'Paid after validity',
  PAYMENT_ON_CANCELLED_BILL: 'Paid on a cancelled bill',
  ACK_UNKNOWN: 'GePG submission outcome unknown',
  CONTROL_NUMBER_DELAY: 'Control number delayed',
  DELIVERY_FAILED: 'Platform did not receive update',
  RECONCILIATION_DIFFERENCE: 'Reconciliation difference',
  DISPUTE: 'Client dispute',
  SECURITY: 'Message failed verification',
  CHANGE_OUTCOME_UNKNOWN: 'GePG change outcome unknown',
  REFUND_FAILED: 'Refund payout failed',
};

export const EVENT_TYPES: Record<string, string> = {
  'bill.created': 'Bill created',
  'bill.approved': 'Bill approved',
  'bill.rejected': 'Bill rejected',
  'bill.control-number.issued': 'Control number issued',
  'bill.registration.rejected': 'GePG rejected the bill',
  'payment.received': 'Payment received',
  'bill.cancelled': 'Bill cancelled',
  'bill.expiry.changed': 'Payment deadline changed',
};

export function stateInfo(state: string | null | undefined): StateInfo {
  if (!state) {
    return { label: '', tone: 'quiet' };
  }
  return STATES[state] ?? { label: state, tone: 'quiet' };
}
