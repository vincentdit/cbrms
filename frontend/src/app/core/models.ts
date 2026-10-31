export interface Me {
  subject: string;
  displayName: string;
  kind: string;
  sourceSystem: string | null;
  roles: string[];
}

export interface BillLine {
  lineNo: number;
  serviceCode: string;
  tariffVersion: number;
  description: string;
  quantity: string;
  unitPrice: string;
  lineTotal: string;
}

export interface Bill {
  billId: string;
  sourceSystem: string;
  sourceReference: string;
  liabilityVersion: number;
  currency: string;
  total: string;
  paymentMode: string;
  description: string;
  preparationState: string;
  externalRegistrationStatus: string;
  collectionState: string;
  releaseState: string;
  controlNumber: string | null;
  dueDate: string;
  validUntil: string;
  issuedAt: string | null;
  lines: BillLine[];
}

export interface BillRow {
  billId: string;
  sourceSystem: string;
  sourceReference: string;
  payerName: string;
  total: string;
  currency: string;
  controlNumber: string | null;
  preparationState: string;
  externalRegistrationStatus: string;
  collectionState: string;
  releaseState: string;
  createdAt: string;
  dueDate: string;
  /** Everything the client has paid against this bill, including any excess held as credit. */
  paid: string;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface PaymentRow {
  paymentId: string;
  transactionId: string;
  amount: string;
  allocated: string;
  channel: string | null;
  paidAt: string;
  receivedAt: string;
}

export interface EventRow {
  sequence: number;
  type: string;
  createdAt: string;
  deliveryStatus: string;
  attempts: number;
}

export interface CaseRow {
  caseId: string;
  type: string;
  status: string;
  billId: string | null;
  sourceReference: string | null;
  reference: string | null;
  value: string | null;
  currency: string | null;
  details: string | null;
  dueDate: string | null;
  openedAt: string;
}

export interface CreditRow {
  creditId: string;
  amount: string;
  status: string;
  refundId: string | null;
  refundStatus: string | null;
}

export interface ChangeRow {
  requestId: string;
  kind: 'CANCELLATION' | 'EXPIRY_CHANGE';
  status: string;
  reason: string;
  newDueDate: string | null;
  requestedBy: string;
  createdAt: string;
}

export interface Activity {
  credits: CreditRow[];
  changes: ChangeRow[];
  payments: PaymentRow[];
  events: EventRow[];
  cases: CaseRow[];
  allocated: string;
  credit: string;
}

export type ApprovalKind = 'BILL' | 'CANCELLATION' | 'EXPIRY_CHANGE' | 'REFUND';

export interface ApprovalRow {
  /** What is being approved. subjectId is the bill, change request or refund ID the decision applies to. */
  kind: ApprovalKind;
  subjectId: string;
  detail: string | null;
  billId: string;
  sourceSystem: string;
  sourceReference: string;
  payerName: string;
  total: string;
  currency: string;
  description: string;
  maker: string;
  requestedAt: string;
}

export interface Summary {
  pendingApprovals: number;
  reconciliationToReview: number;
  refundsToExecute: number;
  openCases: number;
  ackUnknown: number;
  awaitingControlNumber: number;
  failedDeliveries: number;
  pendingDeliveries: number;
  collectedToday: { payments: number; amount: string };
  outstanding: { bills: number; amount: string };
  byCollection: Record<string, number>;
  byRegistration: Record<string, number>;
}

export interface ApiError {
  code: string;
  message: string;
  correlationId: string;
  fieldErrors: { field: string; message: string }[];
}

export interface AppSettings {
  /** Show the GePG simulator page. True only for local and test deployments. */
  simulator: boolean;
  /** "basic" for local development users; "oidc" for Keycloak single sign-on. */
  authMode: 'basic' | 'oidc';
  environmentLabel: string;
  /** Keycloak details, required when authMode is "oidc". */
  oidc?: { url: string; realm: string; clientId: string };
}

export interface ReconciliationRun {
  batchId: string;
  businessDate: string;
  status: 'IMPORTED' | 'MATCHED' | 'REVIEWED';
  matched: number;
  exceptions: number;
  missingInGepg: number;
  externalTotal: string;
  localTotal: string;
  importedAt: string;
  reviewer: string | null;
  reviewedAt: string | null;
  note: string | null;
}

export interface RunResult {
  batchId: string;
  reusedExisting: boolean;
  matched: number;
  exceptions: number;
  missingInGepg: number;
}

export interface ReconciliationItem {
  reference: string;
  controlNumber: string | null;
  amount: string;
  currency: string;
  matchStatus: 'UNMATCHED' | 'MATCHED' | 'EXCEPTION';
  caseId: string | null;
}

export interface RefundRow {
  refundId: string;
  status: string;
  amount: string;
  reason: string;
  billId: string;
  sourceReference: string;
  payerName: string;
  requestedBy: string;
  executionRef: string | null;
  failureReason: string | null;
  createdAt: string;
}

export interface NoticeRow {
  noticeId: string;
  billId: string;
  channel: 'SMS' | 'EMAIL';
  recipient: string;
  template: string;
  language: string;
  body: string;
  status: string;
  createdAt: string;
  sentAt: string | null;
}

export interface ChangeView {
  requestId: string;
  billId: string;
  kind: string;
  status: string;
}

export interface PublicBill {
  controlNumber: string;
  description: string;
  payer: string;
  amount: string;
  paid: string;
  outstanding: string;
  currency: string;
  status: string;
  dueDate: string;
}

export interface PlatformRow {
  code: string;
  name: string;
  status: string;
  bills: number;
  services: string[];
}
