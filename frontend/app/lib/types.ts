// Mirrors the DTOs returned by the Spring backend in com.salonreview.web.dto.*

export type Half = 'FIRST' | 'SECOND';

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
}

export interface SettlementDiagnostics {
  orders: number;
  matchedLineItems: number;
  prepaidMatches: number;
  unmatchedLineItems: number;
  unmatchedRevenue: number;
  cashNotes: number;
}

export interface SettlementPreview {
  year: number;
  month: number;
  timezone: string;
  config: CommissionConfig;
  priceCutoff: number;
  providers: ProviderPayout[];
  diagnostics: SettlementDiagnostics;
}
