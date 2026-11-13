import type { Language } from './types';
import { t } from './i18n';

export type Brk = { start: string; end: string; kind: string };
export type Card = { start: string; end: string; breaks: Brk[] };
export type Finding = { code: string; severity: 'INFO' | 'WARNING' | 'ALERT'; minutes: number; message: string };
export type BreakQuestion = { kind: 'MEAL' | 'REST'; index: number; assumedStart: string | null; assumedEnd: string | null };
export type Answer = { kind: 'MEAL' | 'REST'; index: number; answer: 'YES' | 'NO' | 'DIFF'; start: string | null; end: string | null; reason: string | null };
export type Pattern = { providerId: number; name: string; flaggedDays: number; level: 'NONE' | 'YELLOW' | 'RED' };
export type DayView = {
  date: string;
  providerId: number;
  providerName: string;
  source: 'SQUARE' | 'DRAFT' | 'NONE' | 'VOIDED';
  status: string;
  changedInSquare: boolean;
  timecards: Card[];
  appointments: { start: string; end: string; status: string }[];
  paidMinutes: number;
  regularMinutes: number;
  overtimeMinutes: number;
  doubleTimeMinutes: number;
  mealWaiver: boolean;
  restConfirmed: boolean;
  restNotProvided: boolean;
  waiverOnFile: boolean;
  note: string | null;
  findings: Finding[];
  rateCents: number | null;
  payCents: number | null;
  premiumCents: number | null;
  voidReason?: string | null;
  breakQuestions: BreakQuestion[];
  breaksAssumed: boolean;
  needsAttention: boolean;
  attentionReasons: string[];
  workerAnswers: Answer[];
};
export type Approver = { userId: number; username: string; email: string | null; role: string };
export type ProviderSetup = {
  providerId: number;
  name: string;
  active: boolean;
  workerType: 'W2' | '1099';
  showCommissionComparisonToProvider: boolean;
  hourlyRateCents: number | null;
  rateEffectiveFrom: string | null;
  mobilePhone: string | null;
  payrollAutomationEnabled: boolean;
  alertsEnabled: boolean;
  smsEnabled: boolean;
  approvers: Approver[];
  squareLinked: boolean;
  freeGapAckSignedAt: string | null;
};
export type DayLine = { date: string; source: string; status: string; paidMinutes: number; approved: boolean };
export type ProviderSummary = {
  providerId: number;
  name: string;
  totalMinutes: number;
  regularMinutes: number;
  overtimeMinutes: number;
  doubleTimeMinutes: number;
  regularPayCents: number;
  overtimePayCents: number;
  doubleTimePayCents: number;
  mealPremiumCents: number;
  restPremiumCents: number;
  splitShiftPremiumCents: number;
  grossWagesCents: number;
  tipsCents: number;
  totalToPayCents: number;
  unapprovedDays: number;
  openFindings: number;
  commissionComparisonCents: number | null;
  days: DayLine[];
};
export type Summary = { from: string; to: string; complete: boolean; providers: ProviderSummary[] };
export type Waiver = { signed: boolean; signedAt: string | null; signedName: string | null; textVersion: string; text: string };

export const TZ = 'America/Los_Angeles';

export const usd = (cents: number | null | undefined) =>
  cents == null ? '' : (cents / 100).toLocaleString('en-US', { style: 'currency', currency: 'USD' });
export const hrs = (minutes: number) => (minutes / 60).toFixed(2);

export function hhmm(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(iso));
}

// "2026-11-06" + "09:30" in Los Angeles -> UTC instant, DST-safe.
export function laToIso(date: string, time: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  let guess = Date.UTC(y, m - 1, d, hh, mm);
  for (let i = 0; i < 2; i++) {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: TZ, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).formatToParts(new Date(guess));
    const g = (t: string) => Number(parts.find((p) => p.type === t)!.value);
    const shown = Date.UTC(g('year'), g('month') - 1, g('day'), g('hour') % 24, g('minute'));
    guess += Date.UTC(y, m - 1, d, hh, mm) - shown;
  }
  return new Date(guess).toISOString();
}

export async function tcFetch<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const res = await fetch(`/api/timecards/${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try {
      const j = await res.json();
      msg = j.message || j.error || msg;
    } catch {
      /* not JSON */
    }
    throw new Error(msg);
  }
  return (await res.json()) as T;
}

const STATIC_FINDINGS = ['MEAL_MISSING', 'MEAL_PREMIUM_OWED', 'MEAL_WAIVED', 'SECOND_MEAL_MISSING', 'REST_MISSING', 'REST_PREMIUM_OWED', 'OVERTIME_DAILY', 'DOUBLE_TIME_DAILY'];

export function findingText(lang: Language, f: Finding): string {
  return STATIC_FINDINGS.includes(f.code) ? t(lang, `tcFind${f.code}` as Parameters<typeof t>[1]) : f.message;
}
