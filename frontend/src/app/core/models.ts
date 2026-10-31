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
  /** Still owed (never negative). */
  balance: string;
  /** Excess held for the client as credit. */
  credit: string;
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
  owner: string | null;
  closureReason: string | null;
  evidenceRef: string | null;
  closedAt: string | null;
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

export type ApprovalKind = 'BILL' | 'CANCELLATION' | 'EXPIRY_CHANGE' | 'REFUND' | 'QUOTATION' | 'CATALOGUE' | 'CORRECTION';

export interface ApprovalRow {
  /** What is being approved. subjectId is the bill, change request or refund ID the decision applies to. */
  kind: ApprovalKind;
  subjectId: string;
  detail: string | null;
  /** Null for a quotation, which has no bill yet. */
  billId: string | null;
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

export type GroupBy = 'platform' | 'service' | 'gfs';

export interface CollectionRow {
  key: string;
  name: string;
  payments: number;
  amount: string;
}

export interface CollectionsReport {
  from: string;
  to: string;
  groupBy: GroupBy;
  rows: CollectionRow[];
  applied: string;
  credit: string;
  suspense: string;
  received: string;
  payments: number;
  /** True when payments on multi-line bills were shared across lines (service and GFS views). */
  apportioned: boolean;
}

export interface AgedRow {
  platform: string;
  platformName: string;
  bills: number;
  notYetDue: string;
  days1to30: string;
  days31to60: string;
  days61to90: string;
  over90: string;
  total: string;
}

export interface OutstandingBill {
  billId: string;
  platform: string;
  sourceReference: string;
  payerName: string;
  controlNumber: string | null;
  dueDate: string;
  total: string;
  paid: string;
  balance: string;
  daysOverdue: number;
  bucket: string;
}

export interface OutstandingReport {
  asOf: string;
  rows: AgedRow[];
  totals: AgedRow;
  bills: OutstandingBill[];
}

export interface QuotationMilestone {
  milestone: number;
  description: string;
  amount: string;
  /** The active bill for this milestone, if it has been billed. */
  billId: string | null;
  billState: 'Billed' | 'PartiallyPaid' | 'Paid' | null;
}

export interface Quotation {
  quotationId: string;
  sourceSystem: string;
  quoteReference: string;
  serviceCode: string;
  serviceName: string;
  payerReference: string;
  payerName: string;
  description: string;
  total: string;
  currency: string;
  validUntil: string;
  status: 'Requested' | 'Approved' | 'Rejected' | 'Withdrawn';
  requestedBy: string;
  createdAt: string;
  decidedAt: string | null;
  billed: string;
  milestones: QuotationMilestone[];
}

export type CatalogueKind = 'NEW_SERVICE' | 'NEW_TARIFF' | 'NEW_REVENUE_MAPPING' | 'SERVICE_UPDATE';

export interface CatalogueService {
  code: string;
  name: string;
  nameSw: string | null;
  directorate: string;
  unit: string;
  quantityScale: number;
  requiresApproval: boolean;
  pricingBasis: 'TARIFF' | 'QUOTED';
  status: 'ACTIVE' | 'INACTIVE';
  platforms: string[];
  currentPrice: string | null;
  currentVersion: number | null;
  paymentMode: string | null;
  gfsCode: string | null;
  nextPrice: string | null;
  nextFrom: string | null;
  pendingChangeId: string | null;
}

export interface TariffVersion {
  version: number;
  unitPrice: string;
  paymentMode: string;
  roundingRule: string;
  maxValidityDays: number;
  from: string;
  to: string | null;
  status: string;
  approvalRef: string;
  inEffect: boolean;
}

export interface RevenueMapping {
  gfsCode: string;
  accountingCode: string;
  from: string;
  to: string | null;
  status: string;
  approvalRef: string;
  inEffect: boolean;
}

export interface CatalogueChange {
  changeId: string;
  kind: CatalogueKind;
  serviceCode: string;
  serviceName: string | null;
  status: 'Requested' | 'Applied' | 'Rejected';
  reason: string;
  approvalRef: string | null;
  requestedBy: string;
  createdAt: string;
  appliedAt: string | null;
  currentPrice: string | null;
  proposedPrice: string | null;
  paymentMode: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  gfsCode: string | null;
  accountingCode: string | null;
  summary: string;
}

export interface CatalogueDetail {
  service: CatalogueService;
  tariffs: TariffVersion[];
  mappings: RevenueMapping[];
  changes: CatalogueChange[];
}

export interface Directorate {
  code: string;
  name: string;
}

/** Body of a catalogue proposal; which fields matter depends on kind. */
export interface CatalogueProposal {
  kind: CatalogueKind;
  serviceCode: string;
  reason: string;
  approvalRef?: string | null;
  name?: string | null;
  nameSw?: string | null;
  directorateCode?: string | null;
  unit?: string | null;
  quantityScale?: number | null;
  requiresApproval?: boolean | null;
  pricingBasis?: 'TARIFF' | 'QUOTED' | null;
  serviceStatus?: 'ACTIVE' | 'INACTIVE' | null;
  platforms?: string[] | null;
  unitPrice?: string | null;
  paymentMode?: string | null;
  roundingRule?: string | null;
  maxValidityDays?: number | null;
  gfsCode?: string | null;
  accountingCode?: string | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface UnallocatedPayment {
  paymentId: string;
  transactionId: string;
  controlNumber: string;
  channel: string | null;
  amount: string;
  unallocated: string;
  receivedAt: string;
  caseId: string | null;
  openCorrectionId: string | null;
}

export interface Correction {
  correctionId: string;
  kind: 'ALLOCATE' | 'REVERSE';
  status: 'Requested' | 'Applied' | 'Rejected';
  paymentId: string;
  transactionId: string;
  billId: string;
  sourceSystem: string;
  sourceReference: string;
  payerName: string;
  controlNumber: string | null;
  amount: string;
  reason: string;
  caseId: string | null;
  requestedBy: string;
  createdAt: string;
  appliedAt: string | null;
}

export interface AuditRow {
  id: string;
  occurredAt: string;
  actor: string;
  actorName: string | null;
  action: string;
  objectRef: string;
  reason: string | null;
  correlationId: string | null;
  details: string | null;
}

export interface AuditPage {
  items: AuditRow[];
  total: number;
  page: number;
  size: number;
  from: string;
  to: string;
}

export interface AuditQuery {
  from: string;
  to: string;
  actor: string;
  action: string;
  object: string;
  correlationId: string;
}
