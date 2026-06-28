// Mirrors the DTOs returned by the Spring backend in com.salonreview.web.dto.*

export type Half = 'FIRST' | 'SECOND';

// --- RAG knowledge assistant (com.salonreview.rag / web.Rag*Controller) ---

export interface RagCitation {
  documentId: number | null;
  documentTitle: string;
  citedText: string;
}

export interface RagAnswer {
  answer: string;
  citations: RagCitation[];
  configVersion: number;
  traceRunId: string | null;
  answered: boolean;
}

export interface RagDocumentSummary {
  id: number;
  filename: string;
  sourceType: string;
  status: 'PENDING' | 'INDEXING' | 'INDEXED' | 'QUARANTINED' | 'FAILED';
  statusDetail: string | null;
  indexedChunks: number;
  quarantinedChunks: number;
  createdAt: string;
  indexedAt: string | null;
}

export interface RagAgentConfigDto {
  version: number;
  systemPrompt: string;
  model: string;
  temperature: number;
  k: number;
  distanceThreshold: number;
}

export interface Provider {
  id: number;
  name: string;
  displayName: string;
  commissionRate: number;
  cardTipFeeRate: number;
  active: boolean;
}

export interface PayPeriod {
  id: number;
  year: number;
  month: number;
  half: Half;
  label: string;
}

export interface PeriodEntry {
  id: number;
  providerId: number;
  providerDisplayName: string;
  payPeriodId: number;
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  adjustmentsAmount: number;
  adjustmentsNote: string | null;
  commissionRate: number | null;
}

export interface PayPeriodDetail {
  period: PayPeriod;
  entries: PeriodEntry[];
}

export interface Settlement {
  providerId: number;
  providerName: string;
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  tipsAfterFee: number;
  adjustments: number;
  zelleToProvider: number;
  cashToSalon: number;
  messageText: string;
}

export interface PeriodEntryUpsertRequest {
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  adjustmentsAmount: number;
  adjustmentsNote?: string | null;
  commissionRate?: number | null;
}

export interface PayPeriodCreateRequest {
  year: number;
  month: number;
  half: Half;
}

// --- Square-sourced settlement preview (com.salonreview.square.SettlementPreviewService) ---

export interface CommissionConfig {
  tierServiceThreshold: number;
  baseRate: number;
  tierRate: number;
  cardTipFeeRate: number;
}

export interface HalfSettlement {
  half: Half;
  stage: 'PROVISIONAL_FIRST_HALF' | 'FINAL_MONTH_CLOSE';
  countedServices: number;
  appliedRate: number;
  cardRevenue: number;
  cashCollected: number;
  tipsAfterFee: number;
  adjustments: number;
  tierBonus: number;
  cashTierRebate: number;
  zelleToProvider: number;
  cashToSalon: number;
}

export interface ProviderPayout {
  providerId: number;
  name: string;
  monthCountedServices: number;
  autoQualified: boolean;
  tierManuallyGranted: boolean;
  tierApplied: boolean;
  firstHalf: HalfSettlement;
  secondHalf: HalfSettlement;
  monthZelleToProvider: number;
  monthCashToSalon: number;
  // Provider's response per period (null until they act). Owner/manager see it on the report.
  firstFeedback: Feedback | null;
  secondFeedback: Feedback | null;
  // Copy-pasteable #salary block per half (null if no activity that half).
  firstHalfMessage: string | null;
  secondHalfMessage: string | null;
  // Owner+manager badge counts — every uncleared suspicious booking, per half.
  firstHalfSuspicious: number;
  secondHalfSuspicious: number;
  // Provider self-view badge counts — only bookings with no notes at all (their actionable subset).
  firstHalfSuspiciousNoNotes: number;
  secondHalfSuspiciousNoNotes: number;
}

export interface ServiceLine {
  name: string | null;
  gross: number | null;
}

export interface SuspiciousBooking {
  bookingId: string;
  date: string;           // yyyy-MM-dd
  time: string;           // h:mm a salon-local
  customerId: string;
  customerName: string | null;
  serviceName: string | null;  // combined "A + B + C", convenience fallback
  gross: number | null;        // summed across services
  // Per-segment breakdown — render as chips for at-a-glance scanning. Empty array (not null)
  // when no services resolved.
  services: ServiceLine[];
  half: 'FIRST' | 'SECOND';
  // Free-text notes attached to the appointment in Square (nullable / blank-normalized to null).
  sellerNote: string | null;
  customerNote: string | null;
  cleared: boolean;
  clearedBy: string | null;
  clearedAt: string | null;   // ISO instant
  clearedNote: string | null;
  // Cached AI triage under the current prompt version (null when no triage exists yet).
  triage: TriageResult | null;
}

export interface Feedback {
  status: FeedbackStatus;
  comment: string | null;
}

// --- Accounts & roles (Phase 2) ---

export type Role = 'OWNER' | 'MANAGER' | 'PROVIDER';

export type FeedbackStatus = 'APPROVED' | 'CHANGES_REQUESTED';

export interface Me {
  username: string;
  role: Role;
  providerId: number | null;
  features: Features;
}

export interface Features {
  aiTriageEnabled: boolean;
  ragSuggestionsEnabled: boolean;
}

// Grounded starter prompts for the assistant's empty state (com.salonreview.rag.StarterSuggestions).
export interface StarterTopic {
  label: string;
  questions: string[];
}

export interface StarterSuggestions {
  topics: StarterTopic[];
}

// --- AI triage (suspicious-booking explainer) ---
// Mirrors TriageResult / TriageClassification in com.salonreview.ai.

export type TriageClassification = 'LIKELY_LEGIT' | 'NEEDS_REVIEW' | 'LIKELY_FRAUD';

export interface TriageResult {
  classification: TriageClassification;
  // 0.0 to 1.0 — sent as a JSON number on the wire.
  confidence: number;
  explanation: string;
  // Empty string when classification is LIKELY_LEGIT (no message needed when nothing's wrong).
  draftMessage: string;
  // Detection-signal names the explanation cites (e.g. "past_appointment_no_order").
  signals: string[];
  promptVersion: string;
  model: string;
}

export interface TriageFeedbackRequest {
  helpful: boolean;
  // Owner's corrected classification on a thumbs-down; null when no correction was provided.
  correctedClassification: TriageClassification | null;
}

export interface AppUser {
  id: number;
  username: string;
  role: Role;
  providerId: number | null;
  active: boolean;
  squareTeamMemberId: string | null;
  email: string | null;
}

export interface UserCreateRequest {
  username: string;
  password: string;
  role: Role;
  providerId?: number | null;
  squareTeamMemberId?: string | null;
  email?: string | null;
  name?: string | null;
}

// A Square team member offered as a candidate account in the add-user flow.
export interface SquareRosterEntry {
  teamMemberId: string;
  name: string;
  email: string | null;
  jobTitle: string | null;
  isOwner: boolean;
  suggestedRole: Role;
  providerId: number | null;
  hasAccount: boolean;
}

export interface UserUpdateRequest {
  role?: Role | null;
  active?: boolean | null;
  password?: string | null;
  providerId?: number | null;
}

// --- Per-provider line-level trace (owner/manager drill-down) ---

export interface AttributedService {
  providerId: string; // Square team-member id
  providerName: string;
  date: string;
  half: Half;
  service: string;
  gross: number;
  discount: number;
  net: number;
  tip: number; // this line's share of the transaction (order) tip
  counted: boolean;
  countedUnits: number; // main services this line counts toward the tier (gross >= cutoff)
  units: number; // total services this line represents (incl. add-ons below the cutoff)
  prepaid: boolean;
  channel: 'CARD' | 'CASH' | 'CASH-NOTE' | 'PREPAID' | 'COMP' | 'REDO' | 'MANUAL' | 'NOSHOW';
  time: string | null; // appointment start, salon-local, e.g. "2:30 PM"
  bookingId: string | null; // Square booking/reservation id (for the appointment link)
  customer: string | null; // short client name, e.g. "Donnah P."
}

// A no-show row — one per provider on the booking (a multi-provider no-show splits the fee).
// state: CREDITED (fee detected/paid), NO_FEE (no fee collected), SUPPRESSED (auto-match dismissed),
// CONFIRMED (manager credited an off-signal fee).
export interface NoShowRow {
  bookingId: string;
  providerId: number;
  providerName: string;
  customer: string | null;
  noShowAt: string; // ISO instant of the appointment
  noShowDate: string; // salon-local date (YYYY-MM-DD)
  feeAmount: number | null; // the $25 fee when paid/credited, else null
  feePaidDate: string | null;
  state: 'CREDITED' | 'NO_FEE' | 'SUPPRESSED' | 'CONFIRMED';
}

export interface UnmatchedLine {
  date: string;
  service: string;
  gross: number;
  channel: 'CARD' | 'CASH';
  customerId: string | null;
  customerName: string | null;
}

export interface ProviderDetail {
  year: number;
  month: number;
  providerId: number;
  name: string | null;
  payout: ProviderPayout | null;
  services: AttributedService[];
  unmatched: UnmatchedLine[];
  firstHalfMessage: string | null;
  secondHalfMessage: string | null;
  priceCutoff: number; // a "main service" is gross >= this (e.g. $60)
  timezone: string;
  syncedAt: string; // ISO instant — when this view was pulled live from Square
  noShows: NoShowRow[]; // this provider's no-shows for the month, with fee status
}

export interface SettlementDiagnostics {
  orders: number;
  matchedLineItems: number;
  prepaidMatches: number;
  unmatchedLineItems: number;
  unmatchedRevenue: number;
  cashNotes: number;
  cashNotesSkipped: number; // notes ignored because the appointment was checked out as cash
  ownerComps: number; // services to an owner/family customer credited at menu price (no order)
  ownerCompsSkipped: number; // owner bookings we couldn't value (no catalog price)
}

// --- Owner/family customers (owner/manager) ---

export interface OwnerCustomer {
  id: number;
  squareCustomerId: string;
  name: string | null;
}

export interface CustomerMatch {
  id: string;
  name: string;
}

export interface SettlementPreview {
  year: number;
  month: number;
  timezone: string;
  config: CommissionConfig;
  priceCutoff: number;
  providers: ProviderPayout[];
  diagnostics: SettlementDiagnostics;
  syncedAt: string; // ISO instant — when this report was pulled live from Square
}

// --- Prepaid packages (owner/manager) ---

export interface PrepaidRedemption {
  id: number;
  squareBookingId: string;
  serviceVariationId: string;
  serviceName: string | null;
  serviceDate: string;
  menuPrice: number;
  counts: boolean;
  providerName: string; // the provider who performed this draw-down
}

export interface PrepaidPackage {
  id: number;
  customerId: string | null;
  customerName: string;
  paidDate: string;
  amount: number;
  totalServices: number;
  redeemed: number;
  balance: number;
  status: 'ACTIVE' | 'CLOSED';
  invoiceRef: string | null;
  redemptions: PrepaidRedemption[];
}

export interface PrepaidCandidate {
  bookingId: string;
  serviceVariationId: string;
  serviceName: string;
  date: string;
  time: string | null;
  menuPrice: number;
  counts: boolean;
  teamMemberId: string; // Square team member who performed it — credited on confirm
  providerName: string;
}

export interface PrepaidInvoice {
  id: string;
  number: string | null;
  title: string | null;
  status: string;
  date: string | null;
  amount: number;
}

// --- Redos (owner/manager) ---

export interface Redo {
  id: number;
  originalProviderId: number;
  originalProviderName: string;
  redoProviderId: number;
  redoProviderName: string;
  originalDate: string;
  redoDate: string;
  amount: number;
  serviceName: string | null;
}

export interface RedoCreateRequest {
  originalProviderId: number;
  redoProviderId: number;
  originalDate: string;
  redoDate: string;
  amount: number;
  serviceName?: string | null;
}

// --- Manual service credits (owner/manager) ---

export interface ManualCredit {
  id: number;
  providerId: number;
  providerName: string;
  serviceDate: string;
  gross: number;
  discount: number;
  tip: number;
  serviceName: string | null;
}

export interface ManualCreditCreateRequest {
  providerId: number;
  serviceDate: string;
  gross: number;
  discount: number;
  tip: number;
  serviceName?: string | null;
}

export interface PrepaidCreateRequest {
  customerId?: string | null;
  customerName: string;
  paidDate: string;
  amount: number;
  totalServices: number;
  invoiceRef?: string | null;
}

// --- Revenue pulse: same-period comparison + upcoming projection (owner-only) ---

export interface RevenuePulse {
  year: number;
  month: number;
  currentDays: number;
  currentEndDay: number;
  priorEndDay: number;
  // Wall-clock time cutoff (e.g. "11:22 PM") applied to both current and prior windows when
  // looking at the current month. Null for past months (no time cutoff — full days compared).
  asOfTime: string | null;
  currentGross: number;
  priorGross: number;
  deltaPct: number | null;
  // Non-cancelled upcoming bookings remaining this month.
  upcomingBookings: number;
  upcomingGross: number;
  // currentGross + upcomingGross — naive ceiling, kept as a cross-check.
  projectedMonthGross: number;
  // Forecaster output: blends pattern-match (PeriodEntry) and booking-ceiling calibration (snapshots).
  projectedMid: number;
  projectedLow: number | null;        // null in cold-start mode
  projectedHigh: number | null;       // null in cold-start mode
  forecastCalibrationDataPoints: number;
  forecastHistoryMonths: number;
}

// --- Owner overview dashboard (owner-only) ---

export interface MonthSummary {
  year: number;
  month: number;
  label: string;
  cardRevenue: number | null;
  cashRevenue: number | null;
  grossRevenue: number | null;
  tips: number | null;
  procedures: number;
  avgPerAppt: number | null;
  payrollCost: number | null;
  payrollPct: number | null;
  finalized: boolean;
}

export interface ProviderYtd {
  providerId: number;
  name: string;
  ytdGross: number;
  ytdPayroll: number;
  ytdPayrollPct: number | null;
}

export interface YearTotals {
  totalGross: number;
  totalCard: number;
  totalCash: number;
}

export interface OwnerOverviewData {
  fromYear: number;
  fromMonth: number;
  toYear: number;
  toMonth: number;
  months: MonthSummary[];
  providers: ProviderYtd[];
  prevYear: YearTotals | null;
}

// --- Knowledge Base articles (com.salonreview.web.KbArticleController) ---

export type KbSyncStatus = 'NOT_SYNCED' | 'SYNCED' | 'CHANGED' | 'ERROR';

export interface KbArticle {
  id: number;
  title: string;
  category: string;
  body: string;
  visibleRoles: Role[];
  syncStatus: KbSyncStatus;
  ragDocId: number | null;
  lastSyncedAt: string | null;
  lastSyncedBy: string | null;
  lastSyncError: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface KbWriteRequest {
  title: string;
  category: string;
  body: string;
  visibleRoles: Role[];
}

// --- SOPs (com.salonreview.web.SopController) ---

export type SopAudience = 'MANAGER' | 'PROVIDER' | 'BOTH';
export type SopStatus = 'ACTIVE' | 'ARCHIVED';
export type SopVersionStatus = 'DRAFT' | 'PUBLISHED';

export interface SopVersion {
  id: number;
  versionNumber: number;
  body: string;
  status: SopVersionStatus;
  createdBy: string;
  createdAt: string;
}

export interface Sop {
  id: number;
  title: string;
  category: string;
  audience: SopAudience;
  status: SopStatus;
  currentVersion: SopVersion | null;
  acknowledged: boolean;
  acknowledgedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface SopRosterEntry {
  userId: number;
  username: string;
  role: Role;
  acknowledged: boolean;
  acknowledgedAt: string | null;
}

export interface SopCreateRequest {
  title: string;
  category: string;
  audience: SopAudience;
  body: string;
}

export interface SopUpdateRequest {
  title: string;
  category: string;
  audience: SopAudience;
}
