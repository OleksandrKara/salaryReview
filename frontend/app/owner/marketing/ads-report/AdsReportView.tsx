'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api } from '../../../lib/api';
import { t } from '../../../lib/i18n';
import type {
  AdSpendEntry,
  Language,
  MarketingAdsReportData,
  MarketingAdsReportPeriod,
  MarketingAnalyticsData,
  MarketingAnalyticsSegment,
  MarketingCancelledAppointment,
  MarketingCompletedAppointment,
  MarketingCustomerHistory,
  MarketingLandingPage,
  TrafficSourceKey,
} from '../../../lib/types';
import { Spinner } from '../../../components/Spinner';
import TrafficSourceFilter, { ADS_ONLY_SOURCES } from '../TrafficSourceFilter';
import { AppointmentHistoryList, HistoryToggle, PAYMENT_CHANNEL_LABELS, PaymentChannelBadge, SubmissionHistoryList } from '../ContactHistory';
import PeriodFilter from '../PeriodFilter';
import { lastNMonthsRange, lastNWeeksRange, monthToDateSoFarRange, parsePeriodParams, todayIso } from '../period';
import type { PeriodSelection, PeriodType } from '../period';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usdExact = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// Parses a plain yyyy-MM-dd as local, not UTC-shifted (a UTC parse of a bare date can land on the
// previous day in western timezones).
function parseLocalDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function mondayOnOrBefore(d: Date): Date {
  const day = d.getDay(); // 0 = Sunday .. 6 = Saturday
  const diff = day === 0 ? 6 : day - 1; // days since the preceding Monday
  const monday = new Date(d);
  monday.setDate(d.getDate() - diff);
  return monday;
}

// Seeded from the salon's own business timezone (see period.ts's todayIso), not the browser's —
// parseLocalDate turns that into a Date whose local getters/setters (getDay, setDate, etc.
// below) then stay internally consistent with that Pacific calendar date.
function thisWeekRange(): { from: string; to: string } {
  const monday = mondayOnOrBefore(parseLocalDate(todayIso()));
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  return { from: isoDate(monday), to: isoDate(sunday) };
}

function thisMonthRange(): { from: string; to: string } {
  const today = parseLocalDate(todayIso());
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  const to = new Date(today.getFullYear(), today.getMonth() + 1, 0);
  return { from: isoDate(from), to: isoDate(to) };
}

function fmtPeriodLabel(row: MarketingAdsReportPeriod, periodType: MarketingAdsReportData['periodType']): string {
  const start = parseLocalDate(row.periodStart);
  if (periodType === 'MONTH') {
    return start.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  }
  if (periodType === 'MONTH_TO_DATE') {
    return `${start.toLocaleDateString('en-US', { month: 'long', year: 'numeric' })} (so far)`;
  }
  const end = parseLocalDate(row.periodEnd);
  const sameMonth = start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
  const startLabel = start.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const endLabel = end.toLocaleDateString('en-US', sameMonth ? { day: 'numeric' } : { month: 'short', day: 'numeric' });
  return `${startLabel} – ${endLabel}`;
}

function fmtDateRange(fromIso: string, toIso: string): string {
  const from = parseLocalDate(fromIso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  const to = parseLocalDate(toIso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  return `${from} – ${to}`;
}

function isCurrentPeriod(row: MarketingAdsReportPeriod): boolean {
  const today = todayIso();
  return today >= row.periodStart && today <= row.periodEnd;
}

function roiMultiple(spend: number, revenue: number): number | null {
  return spend > 0 ? revenue / spend : null;
}

function roiLabel(roi: number | null): string {
  return roi === null ? '—' : `${roi.toFixed(1)}x`;
}

// Collected + everything still to come, in or out of this period — the same sum the WhatsApp
// text export calls "Total", reused so the table view's Total column matches it exactly.
function totalRevenueOf(row: MarketingAdsReportPeriod): number {
  return row.revenueCollected + row.anticipatedRevenue + row.anticipatedRevenueOutsidePeriod;
}

function roiPercentLabel(pct: number | null): string {
  return pct === null ? '—' : `${pct >= 0 ? '+' : ''}${pct.toFixed(0)}%`;
}

// Realized ROAS (spend vs. what's actually been collected), Total ROAS (spend vs. the full
// Collected+Anticipated Total above), and ROI% (profit over spend, using that same Total) — the
// three figures the WhatsApp text export already shows together under "*Ad spend & ROI*", reused
// here so the table view and top summary say exactly the same thing in the same terms.
function roiMetricsOf(row: MarketingAdsReportPeriod): {
  realizedRoas: number | null;
  totalRoas: number | null;
  roiPercent: number | null;
} {
  const totalRevenue = totalRevenueOf(row);
  return {
    realizedRoas: roiMultiple(row.adSpend, row.revenueCollected),
    totalRoas: roiMultiple(row.adSpend, totalRevenue),
    roiPercent: row.adSpend > 0 ? ((totalRevenue - row.adSpend) / row.adSpend) * 100 : null,
  };
}

// What happened to every booking dated in this period, for these ads-attributed customers: it
// either already got rung up (Completed), never happened (Cancelled — cancelled by either side,
// declined, or no-show), or hasn't happened yet (Anticipated). The three always add up to Total,
// so nothing about "what happened to these bookings" is left unaccounted for.
function bookingsBreakdownOf(row: MarketingAdsReportPeriod): {
  total: number;
  completed: number;
  cancelled: number;
  anticipated: number;
  anticipatedOutsidePeriod: number;
  cancelledPercent: number | null;
} {
  const completed = row.completedAppointments;
  const cancelled = row.cancelledBookings;
  const anticipated = row.anticipatedAppointments;
  const anticipatedOutsidePeriod = row.anticipatedAppointmentsOutsidePeriod;
  const total = completed + cancelled + anticipated + anticipatedOutsidePeriod;
  return {
    total, completed, cancelled, anticipated, anticipatedOutsidePeriod,
    cancelledPercent: total > 0 ? (cancelled / total) * 100 : null,
  };
}

// Same shape as bookingsBreakdownOf, one level up: distinct people instead of appointments. A
// customer who falls into more than one bucket (e.g. one visit completed, another still
// upcoming) is counted in each — see adsCustomersInfo — so `total` here is a sum of the four
// buckets, not a deduped headcount across them (same convention bookingsBreakdownOf already
// uses, where the four buckets are inherently disjoint since one appointment is one bucket).
function customersBreakdownOf(row: MarketingAdsReportPeriod): {
  total: number;
  completed: number;
  cancelled: number;
  anticipated: number;
  anticipatedOutsidePeriod: number;
} {
  const completed = row.customersCollected;
  const cancelled = row.customersCancelled;
  const anticipated = row.customersAnticipated;
  const anticipatedOutsidePeriod = row.customersAnticipatedOutsidePeriod;
  return { total: completed + cancelled + anticipated + anticipatedOutsidePeriod, completed, cancelled, anticipated, anticipatedOutsidePeriod };
}

// First-visit vs. repeat sub-line shown under a Revenue/Bookings term — same shape for a dollar
// figure (MoneySplit) or a headline count (CountSplit), since both are just {firstVisit, repeat}.
// "First visit" is always the same freshFromAds check used everywhere else in Ads Report/Analytics,
// never a separate definition, so this line always agrees with e.g. customersCreated above it.
function splitLabel(split: { firstVisit: number; repeat: number }, fmt: (n: number) => string): string {
  return `First-visit ${fmt(split.firstVisit)} · Repeat ${fmt(split.repeat)}`;
}

// Revenue (collected/anticipated/total) and, when there's any ad spend to report against, ROI
// (realized vs total ROAS/ROI%) — the money-and-ROI half of what MarketingAdsReportDto.PeriodRow
// carries; the Bookings breakdown below is the other half. customersCreated/customersFollowedUp
// aren't surfaced here — see the removal note above MoneyBreakdown/ROIBreakdown/BookingsBreakdown.
// Visits/clicks/leads/unbooked (the Funnel-sourced part of the manual reports this mirrors) aren't
// wired up per-period yet — see openspec/changes/ads-report-consolidation/design.md D7's scope
// note — so they're left out here rather than faked.
function formatWhatsAppReport(
  row: MarketingAdsReportPeriod,
  periodType: MarketingAdsReportData['periodType'],
  showRoi: boolean,
  slug?: string,
): string {
  const totalRevenue = totalRevenueOf(row);
  const bookings = bookingsBreakdownOf(row);
  const customers = customersBreakdownOf(row);

  const lines: string[] = [];
  lines.push(`*Ads Report${slug ? ` — ${slug}` : ''}*`);
  lines.push(`${fmtPeriodLabel(row, periodType)}${row.monthInProgress ? ' (in progress)' : ''}`);
  lines.push('');
  lines.push('*Revenue*');
  lines.push(`Collected: ${usdExact(row.revenueCollected)}`);
  lines.push(`  ${splitLabel(row.revenueCollectedSplit, usdExact)}`);
  lines.push(`Anticipated (this period only): ${usdExact(row.anticipatedRevenue)}`);
  lines.push(`  ${splitLabel(row.anticipatedRevenueSplit, usdExact)}`);
  lines.push(`Anticipated (outside period): ${usdExact(row.anticipatedRevenueOutsidePeriod)}`);
  lines.push(`  ${splitLabel(row.anticipatedRevenueOutsidePeriodSplit, usdExact)}`);
  lines.push(`Total: ${usdExact(totalRevenue)}`);
  if (showRoi) {
    const { realizedRoas, totalRoas, roiPercent } = roiMetricsOf(row);
    lines.push('');
    lines.push('*Ad spend & ROI*');
    lines.push(`Spend: ${row.adSpendEstimated ? '~' : ''}${usdExact(row.adSpend)}`);
    lines.push(`Realized ROAS: ${roiLabel(realizedRoas)}`);
    lines.push(`Total ROAS: ${roiLabel(totalRoas)}`);
    if (roiPercent !== null) lines.push(`ROI: ${roiPercentLabel(roiPercent)}`);
  }
  lines.push('');
  lines.push('*Bookings*');
  lines.push(`Completed: ${bookings.completed}`);
  lines.push(`  ${splitLabel(row.completedAppointmentsSplit, String)}`);
  lines.push(`Cancelled: ${bookings.cancelled}${bookings.cancelledPercent !== null ? ` (${bookings.cancelledPercent.toFixed(0)}%)` : ''}`);
  lines.push(`Anticipated (this period): ${bookings.anticipated}`);
  lines.push(`  ${splitLabel(row.anticipatedAppointmentsSplit, String)}`);
  lines.push(`Anticipated (outside period): ${bookings.anticipatedOutsidePeriod}`);
  lines.push(`  ${splitLabel(row.anticipatedAppointmentsOutsidePeriodSplit, String)}`);
  lines.push(`Total: ${bookings.total}`);
  lines.push('');
  lines.push('*Customers*');
  lines.push(`Completed: ${customers.completed}`);
  lines.push(`Cancelled: ${customers.cancelled}`);
  lines.push(`Anticipated (this period): ${customers.anticipated}`);
  lines.push(`Anticipated (outside period): ${customers.anticipatedOutsidePeriod}`);
  lines.push(`Total: ${customers.total}`);
  return lines.join('\n');
}

// --- Appointment ledger (drill-down popup + "View breakdown") ---
//
// Every figure in the Revenue/Bookings blocks above is a sum or count over some set of individual
// appointments — this section normalizes MarketingAnalyticsData's three separate lists (completed/
// upcoming/cancelled, three different shapes) into one common row shape, tagged with exactly the
// category a reader would expect to find it under if they clicked that figure. Two UIs share this:
// a per-figure popup (LedgerModal, "look at this one number") and the "View breakdown" section
// (BreakdownDrilldown, "look at everything at once") — see AppointmentLedger below.

type LedgerFilter = 'all' | 'completed' | 'anticipated-period' | 'anticipated-outside' | 'cancelled-period' | 'cancelled-outside';

interface LedgerRow {
  key: string;
  customerId: string;
  customerName: string;
  serviceName: string;
  /** ISO-8601 date (yyyy-MM-dd) — the day this row is "dated" on, whatever category it's in. */
  date: string;
  /** Pre-formatted for display: a still-upcoming appointment shows its actual booked time
   * (fmtAppointment, "Today · 2:30 PM") since that's the whole point of an anticipated row; a
   * completed/cancelled one — already in the past — just shows the day (fmtDay). */
  dateLabel: string;
  amount: number;
  /** Whether `amount` is a real collected total or a catalog-price estimate (upcoming/cancelled
   * appointments haven't actually been paid, so there's nothing real to report there). */
  amountKind: 'collected' | 'estimate';
  category: Exclude<LedgerFilter, 'all'>;
  freshFromAds: boolean;
  paymentChannel?: MarketingCompletedAppointment['paymentChannel'];
  cancellationStatus?: MarketingCancelledAppointment['status'];
}

const LEDGER_FILTERS: LedgerFilter[] = [
  'all', 'completed', 'anticipated-period', 'anticipated-outside', 'cancelled-period', 'cancelled-outside',
];

const LEDGER_FILTER_LABELS: Record<LedgerFilter, string> = {
  all: 'All',
  completed: 'Collected',
  'anticipated-period': 'Anticipated (period)',
  'anticipated-outside': 'Anticipated (outside)',
  'cancelled-period': 'Cancelled',
  'cancelled-outside': 'Cancelled (outside)',
};

/** Builds the unified row list from a MarketingAnalyticsData response. Anticipated/Cancelled rows
 * whose date falls outside [data.from, data.to] AND whose customer wasn't captured (firstTouch)
 * within that same window are silently dropped — exactly the set the Ads Report's own headline
 * "Anticipated (outside period)"/Cancelled figures exclude too (see MarketingAnalyticsService's
 * capturedInRange doc comments), so this ledger's own totals reconcile against those figures
 * exactly rather than showing a bigger, harder-to-explain number.
 */
function buildLedgerRows(data: MarketingAnalyticsData): LedgerRow[] {
  const rows: LedgerRow[] = [];
  for (const c of data.completed) {
    rows.push({
      // bookingId, not date+serviceName — two genuinely different same-day cash-note
      // appointments for one customer both get the identical generic serviceName "cash note (N
      // counted)", so date+serviceName alone can collide (a real production case: Ashanti
      // Williamson's two same-day cash visits shared a key, causing a React duplicate-key bug —
      // a ghost row on ledger-tab switches whose expand state was shared with the real row).
      key: `completed-${c.bookingId ?? `${c.customerId}-${c.date}-${c.serviceName}`}`,
      customerId: c.customerId, customerName: c.customerName, serviceName: c.serviceName,
      date: c.date, dateLabel: fmtDay(c.date), amount: c.collected, amountKind: 'collected', category: 'completed',
      freshFromAds: c.freshFromAds, paymentChannel: c.paymentChannel,
    });
  }
  for (const u of data.upcoming) {
    const date = u.startAt.slice(0, 10);
    // Period membership keys on startAt (when the visit happens), not bookedAt (when the
    // reservation was made) — matching the Ads Report's own period-row bucketing (see
    // MarketingAnalyticsDto.UpcomingAppointment.bookedAt's own doc: fixed backend-side 2026-08-27
    // to bucket by startAt so a customer who booked late in one period for a visit landing in the
    // next period is counted as anticipated in the period their visit actually falls in, not the
    // one they booked in). This file's own inPeriod check was never updated to match at the time,
    // which silently mis-sorted every such row into "Anticipated (period)" here while the
    // headline figures (already on startAt) correctly excluded it — the two disagreed on both
    // count and $ total for the exact same underlying appointments.
    const inPeriod = date >= data.from && date <= data.to;
    if (!inPeriod && !u.capturedInRange) continue;
    rows.push({
      key: `upcoming-${u.bookingId ?? `${u.customerId}-${u.startAt}`}`,
      customerId: u.customerId, customerName: u.customerName, serviceName: u.serviceName,
      date, dateLabel: fmtAppointment(u.startAt), amount: u.price, amountKind: 'estimate',
      category: inPeriod ? 'anticipated-period' : 'anticipated-outside',
      freshFromAds: u.freshFromAds,
    });
  }
  for (const c of data.cancelled) {
    // Same reasoning as upcoming above — bucket by bookedDate, not the cancelled visit's own date.
    const inPeriod = c.bookedDate >= data.from && c.bookedDate <= data.to;
    if (!inPeriod && !c.capturedInRange) continue;
    rows.push({
      key: `cancelled-${c.bookingId ?? `${c.customerId}-${c.date}-${c.serviceName}`}`,
      customerId: c.customerId, customerName: c.customerName, serviceName: c.serviceName,
      date: c.date, dateLabel: fmtDay(c.date), amount: c.price, amountKind: 'estimate',
      category: inPeriod ? 'cancelled-period' : 'cancelled-outside',
      freshFromAds: c.freshFromAds, cancellationStatus: c.status,
    });
  }
  // Most-recent first throughout, including "All" — a still-upcoming row's date is today or
  // later, so it naturally floats to the top ("what's coming up"), with history below it, the
  // same reading order a bank statement uses.
  rows.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  return rows;
}

function countLedgerRows(rows: LedgerRow[]): Record<LedgerFilter, number> {
  const counts: Record<LedgerFilter, number> = {
    all: rows.length, completed: 0, 'anticipated-period': 0, 'anticipated-outside': 0,
    'cancelled-period': 0, 'cancelled-outside': 0,
  };
  for (const r of rows) counts[r.category] += 1;
  return counts;
}

const CANCELLATION_STATUS_LABELS: Record<MarketingCancelledAppointment['status'], string> = {
  CANCELLED_BY_CUSTOMER: 'Cancelled by customer',
  CANCELLED_BY_SELLER: 'Cancelled by salon',
  DECLINED: 'Declined',
  NO_SHOW: 'No-show',
};

type ViewMode = 'table' | 'text' | 'chart';

const WEEK_PRESETS = [3, 8, 12];
const MONTH_PRESETS = [3, 6, 12];
const DEFAULT_SLUG = 'mani';

/** Fetches MarketingAnalyticsData on demand — not on mount — since it's a live, per-customer
 * Square sweep (bookingsForCustomer for every ads-attributed contact), the same reason "View
 * breakdown" has always been an opt-in expand rather than loaded eagerly. `ensureLoaded()` is
 * idempotent and safe to call from multiple places (a popup click, "View breakdown" opening) —
 * once triggered, later changes to from/to/sources/slug still refetch, but no second fetch fires
 * just because a second consumer also wants the data that's already loading/loaded. Shared by both
 * consumers so they show the exact same snapshot and never double the Square load.
 */
function useAnalyticsBreakdown(from: string, to: string, sources: Set<TrafficSourceKey>, slug?: string) {
  const [data, setData] = useState<MarketingAnalyticsData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [triggered, setTriggered] = useState(false);

  useEffect(() => {
    if (!triggered) return;
    let cancelled = false;
    async function load() {
      setLoading(true);
      setError('');
      try {
        const result = await api.getMarketingAnalytics(from, to, sources, slug);
        if (!cancelled) { setData(result); setError(''); }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load breakdown.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    // Deferred a microtask out so setLoading(true) doesn't run synchronously inside the effect
    // body itself — same pattern AdsReportView's own mount-fetch effect already uses below.
    void Promise.resolve().then(load);
    return () => { cancelled = true; };
  }, [triggered, from, to, sources, slug]);

  return { data, loading, error, ensureLoaded: () => setTriggered(true) };
}

export default function AdsReportView({
  initialData,
  slug,
  language,
}: {
  initialData: MarketingAdsReportData;
  slug?: string;
  language?: Language | null;
}) {
  const lang = language ?? null;
  const [data, setData] = useState(initialData);
  // Keeps this component in sync after MarketingTabs' shared "Sync appointments" button triggers
  // a router.refresh() — that re-runs the server component above us with fresh follow-up-aware
  // data, but a plain useState(initialData) only reads its argument on first mount, so without
  // this the already-mounted view would keep showing stale numbers. Adjusting state during render
  // (not inside an effect) is React's own documented pattern for "reset state when a prop changes".
  const [prevInitialData, setPrevInitialData] = useState(initialData);
  if (initialData !== prevInitialData) {
    setPrevInitialData(initialData);
    setData(initialData);
  }

  // Seeded from the URL (?period=&from=&to=) so a page reload or a tab switch back to Ads Report
  // restores the same filter instead of always resetting to Month to date — see PeriodFilter and
  // ../period's parsePeriodParams, shared by every marketing tab.
  const searchParams = useSearchParams();
  const initialSelection = parsePeriodParams(searchParams);
  const [periodType, setPeriodType] = useState<PeriodType>(initialSelection.period);
  // Not URL-persisted — resets to the default 3 weeks/6 months on reload, same as before this
  // change; only Ads Report ever reaches 'week'/'month' at all (see PeriodFilter's
  // enableWeekMonth), so there's no cross-tab consistency to preserve here. Weekly defaults to
  // 3 (not a wider window) to keep the Square sweep behind every weekly load fast — the 8/12-week
  // presets are still one tap away for a longer look-back.
  const [rangeCount, setRangeCount] = useState(3);
  const [customFrom, setCustomFrom] = useState(initialSelection.period === 'custom' ? initialSelection.from ?? '' : '');
  const [customTo, setCustomTo] = useState(initialSelection.period === 'custom' ? initialSelection.to ?? '' : '');
  const [sources, setSources] = useState<Set<TrafficSourceKey>>(() => new Set(ADS_ONLY_SOURCES));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [view, setView] = useState<ViewMode>('table');
  const [showBreakdown, setShowBreakdown] = useState(false);

  function computeRange(pt: 'week' | 'month', n: number) {
    return pt === 'week' ? lastNWeeksRange(n) : lastNMonthsRange(n);
  }

  async function load(nextPeriodType: PeriodType, nextSources: Set<TrafficSourceKey>, from?: string, to?: string) {
    setLoading(true);
    setError('');
    try {
      const result = await api.getMarketingAdsReport(nextPeriodType, from, to, nextSources, slug);
      setData(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load ads report.');
    } finally {
      setLoading(false);
    }
  }

  // Always re-fetches on mount, for whatever the initial filter is (from the URL, Month to date by
  // default) — the server-rendered initialData above is already correct, but a guaranteed fresh
  // client fetch (with the loading banner visibly shown) means the owner never has to wonder
  // whether what they're looking at is current, and it self-heals any staleness in initialData (a
  // cached page, a slow-to-update session) without needing a manual filter click first.
  useEffect(() => {
    // Deferred a microtask out (rather than calling load() directly) so its setLoading(true)
    // doesn't run synchronously inside the effect body itself.
    void Promise.resolve().then(() => load(periodType, sources, customFrom || undefined, customTo || undefined));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The single handler PeriodFilter's onChange calls — consolidates what used to be
  // selectPeriodType + applyCustomRange, since PeriodFilter now owns the custom from/to inputs
  // itself and only ever calls back once a selection is actually confirmed (immediately for
  // All/Weekly/Monthly/Month to date; only after "Apply" for Custom).
  function onPeriodChange(selection: PeriodSelection) {
    setPeriodType(selection.period);
    if (view === 'chart' && selection.period !== 'month') setView('table');
    if (selection.period === 'week' || selection.period === 'month') {
      const defaultN = selection.period === 'week' ? 3 : 6;
      setRangeCount(defaultN);
      const range = computeRange(selection.period, defaultN);
      void load(selection.period, sources, range.from, range.to);
    } else if (selection.period === 'mtd') {
      void load('mtd', sources);
    } else if (selection.period === 'all') {
      void load('all', sources);
    } else if (selection.period === 'custom' && selection.from && selection.to) {
      setCustomFrom(selection.from);
      setCustomTo(selection.to);
      void load('custom', sources, selection.from, selection.to);
    }
  }

  function selectRangePreset(n: number) {
    setRangeCount(n);
    const range = computeRange(periodType as 'week' | 'month', n);
    void load(periodType, sources, range.from, range.to);
  }

  function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    if (periodType === 'week' || periodType === 'month') {
      const range = computeRange(periodType, rangeCount);
      void load(periodType, next, range.from, range.to);
    } else if (periodType === 'mtd') {
      void load('mtd', next);
    } else if (periodType === 'all') {
      void load('all', next);
    } else if (periodType === 'custom' && customFrom && customTo) {
      void load('custom', next, customFrom, customTo);
    }
  }

  const totals = data.totals;
  // ROI is only meaningful once there's spend to measure a return against — showing "Realized
  // ROAS: —" etc. for a page with no ad spend entered is noise, not information. A single flag
  // derived from the totals row (a sum across every visible period) keeps the top summary and
  // the table's ROI columns all agreeing on whether to show it.
  const showRoi = totals.adSpend > 0;

  // Which single period (week/month) the Text view's WhatsApp export is for — defaults to the
  // most recent *completed* one (periods arrives most-recent-first, so "last week"/"last month",
  // not whatever's still in progress) rather than always the aggregate across the whole visible
  // range, so a report for one specific past week is one tap away instead of requiring a Custom
  // date-range round trip. Only meaningful for week/month grain, where periods has more than one
  // row — mtd/custom/all already return exactly one row, identical to totals.
  function defaultTextPeriodStart(periods: MarketingAdsReportPeriod[]): string | null {
    if (periods.length === 0) return null;
    return (periods.find((p) => !isCurrentPeriod(p)) ?? periods[0]).periodStart;
  }
  const [selectedTextPeriodStart, setSelectedTextPeriodStart] = useState(() => defaultTextPeriodStart(data.periods));
  const [prevPeriodsForText, setPrevPeriodsForText] = useState(data.periods);
  if (data.periods !== prevPeriodsForText) {
    setPrevPeriodsForText(data.periods);
    setSelectedTextPeriodStart(defaultTextPeriodStart(data.periods));
  }
  const textRow = data.periods.find((p) => p.periodStart === selectedTextPeriodStart) ?? totals;

  // Shared by the per-figure popup and "View breakdown" below, so both ever only fetch once and
  // always agree on what they show — see useAnalyticsBreakdown's own comment.
  const analyticsBreakdown = useAnalyticsBreakdown(totals.periodStart, totals.periodEnd, sources, slug);
  const [popupFilter, setPopupFilter] = useState<LedgerFilter | null>(null);
  function openLedgerPopup(filter: LedgerFilter) {
    analyticsBreakdown.ensureLoaded();
    setPopupFilter(filter);
  }

  return (
    <div>
      <TrafficSourceFilter
        selected={sources}
        onChange={changeSources}
        description="Ad spend, revenue, and volume for the selected source(s) — defaults to Meta & Google ad clicks."
        disabled={loading}
      />

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <PeriodFilter
          value={{ period: periodType, from: customFrom || undefined, to: customTo || undefined }}
          onChange={onPeriodChange}
          enableWeekMonth
          disabled={loading}
        />
        {(periodType === 'week' || periodType === 'month') && (
          <div className="flex flex-wrap gap-2">
            {(periodType === 'week' ? WEEK_PRESETS : MONTH_PRESETS).map((n) => (
              <PresetButton
                key={n}
                label={periodType === 'week' ? `Last ${n} weeks` : `Last ${n} months`}
                active={rangeCount === n}
                disabled={loading}
                onClick={() => selectRangePreset(n)}
              />
            ))}
          </div>
        )}
      </div>

      {error ? <p className="mt-3 text-sm text-red-600">{error}</p> : null}

      {loading && (
        <div className="sticky top-2 z-20 mt-4 flex items-center gap-2 rounded-lg bg-blue-50 px-3 py-2 text-sm font-medium text-blue-700 shadow-md ring-1 ring-blue-200">
          <Spinner className="h-4 w-4" />
          Updating report — this can take a few seconds, especially with no page selected above…
        </div>
      )}

      <p className="mt-4 text-xs text-zinc-500">
        {data.periods.length > 0 ? fmtDateRange(totals.periodStart, totals.periodEnd) : ''}
      </p>

      <div className={`mt-4 flex flex-col gap-3 transition-opacity ${loading ? 'opacity-50' : ''}`}>
        <MoneyBreakdown row={totals} layout="horizontal" lang={lang} onExpand={openLedgerPopup} />
        {showRoi && <ROIBreakdown row={totals} layout="horizontal" lang={lang} />}
        <BookingsBreakdown row={totals} layout="horizontal" lang={lang} onExpand={openLedgerPopup} />
        <CustomersBreakdown row={totals} layout="horizontal" lang={lang} onExpand={openLedgerPopup} />
      </div>

      {data.periods.length === 0 ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No data for this range yet.
        </div>
      ) : (
        // Dimmed (not just the stat cards above) while a fetch is in flight — otherwise the Text
        // view in particular looks fully current while it's actually about to be replaced, and a
        // copy-paste mid-fetch silently grabs the stale numbers. Pointer-events disabled too, so
        // a click on a now-stale Copy button (or a table row) can't fire against data about to change.
        <div className={`transition-opacity ${loading ? 'pointer-events-none opacity-50' : ''}`}>
          <div className="mt-6 flex items-center justify-between gap-3">
            <ViewSwitcher view={view} onChange={setView} showChart={periodType === 'month'} />
          </div>

          {view === 'table' && <PeriodTable periods={data.periods} periodType={data.periodType} lang={lang} showRoi={showRoi} />}
          {view === 'text' && (
            <>
              {data.periods.length > 1 && (
                <label className="mt-4 flex flex-col gap-1 text-xs">
                  <span className="font-medium text-zinc-500">Period</span>
                  <select
                    value={selectedTextPeriodStart ?? ''}
                    onChange={(e) => setSelectedTextPeriodStart(e.target.value)}
                    className="w-fit rounded border border-zinc-300 px-2 py-1.5 text-sm text-zinc-900"
                  >
                    {data.periods.map((p) => (
                      <option key={p.periodStart} value={p.periodStart}>
                        {fmtPeriodLabel(p, data.periodType)}
                        {isCurrentPeriod(p) ? ' (in progress)' : ''}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <WhatsAppTextView row={textRow} periodType={data.periodType} slug={slug} showRoi={showRoi} />
            </>
          )}
          {view === 'chart' && periodType === 'month' && <TrendChart periods={data.periods} />}
        </div>
      )}

      <div className="mt-8 border-t border-zinc-100 pt-6">
        <button
          type="button"
          onClick={() => {
            setShowBreakdown((v) => !v);
            analyticsBreakdown.ensureLoaded();
          }}
          className="text-sm font-medium text-blue-600 hover:underline"
        >
          {showBreakdown ? 'Hide breakdown' : 'View breakdown'}
        </button>
        {showBreakdown && (
          <BreakdownDrilldown
            from={totals.periodStart}
            to={totals.periodEnd}
            data={analyticsBreakdown.data}
            loading={analyticsBreakdown.loading}
            error={analyticsBreakdown.error}
          />
        )}
      </div>

      <AdSpendEntryForm slug={slug} />

      {popupFilter && (
        <LedgerModal
          filter={popupFilter}
          onClose={() => setPopupFilter(null)}
          data={analyticsBreakdown.data}
          loading={analyticsBreakdown.loading}
          error={analyticsBreakdown.error}
        />
      )}
    </div>
  );
}

function ViewSwitcher({ view, onChange, showChart }: { view: ViewMode; onChange: (v: ViewMode) => void; showChart: boolean }) {
  return (
    <div className="inline-flex gap-1 rounded-lg bg-zinc-100 p-1">
      <PeriodTypeButton label="Table" active={view === 'table'} onClick={() => onChange('table')} />
      <PeriodTypeButton label="Text" active={view === 'text'} onClick={() => onChange('text')} />
      {showChart && <PeriodTypeButton label="Chart" active={view === 'chart'} onClick={() => onChange('chart')} />}
    </div>
  );
}

function WhatsAppTextView({
  row, periodType, slug, showRoi,
}: {
  row: MarketingAdsReportPeriod;
  periodType: MarketingAdsReportData['periodType'];
  slug?: string;
  showRoi: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const text = useMemo(() => formatWhatsAppReport(row, periodType, showRoi, slug), [row, periodType, showRoi, slug]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied by the browser — the text is still visible to select by hand.
    }
  }

  return (
    <div className="mt-4">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-zinc-500">
          Ready to paste into WhatsApp — for {fmtDateRange(row.periodStart, row.periodEnd)}.
        </span>
        <button
          type="button"
          onClick={copy}
          className="rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-zinc-700"
        >
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </div>
      <pre className="mt-2 whitespace-pre-wrap rounded-lg bg-zinc-50 p-4 text-sm text-zinc-800 ring-1 ring-zinc-200">
        {text}
      </pre>
    </div>
  );
}

const CHART_COLORS = { spend: '#2563eb', collected: '#059669', anticipated: '#d97706' };

function TrendChart({ periods }: { periods: MarketingAdsReportPeriod[] }) {
  const chartData = useMemo(
    () =>
      [...periods]
        .reverse() // periods arrive most-recent-first; a trend reads left-to-right, earliest first
        .map((row) => ({
          month: parseLocalDate(row.periodStart).toLocaleDateString('en-US', { month: 'short', year: '2-digit' }),
          adSpend: row.adSpend,
          revenueCollected: row.revenueCollected,
          anticipatedRevenue: row.anticipatedRevenue,
        })),
    [periods],
  );

  return (
    <div className="mt-4 rounded-lg p-3 ring-1 ring-zinc-200 sm:p-4">
      <div style={{ width: '100%', height: 320 }}>
        <ResponsiveContainer>
          <ComposedChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
            <XAxis dataKey="month" tick={{ fontSize: 12, fill: '#71717a' }} axisLine={{ stroke: '#e4e4e7' }} tickLine={false} />
            <YAxis
              tick={{ fontSize: 12, fill: '#71717a' }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(v: number) => usd(v)}
              width={64}
            />
            <Tooltip formatter={(value) => usdExact(Number(value))} contentStyle={{ fontSize: 12, borderRadius: 8 }} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Bar dataKey="adSpend" name="Ad spend" fill={CHART_COLORS.spend} radius={[3, 3, 0, 0]} />
            <Bar dataKey="revenueCollected" name="Collected" fill={CHART_COLORS.collected} radius={[3, 3, 0, 0]} />
            <Line
              type="monotone"
              dataKey="anticipatedRevenue"
              name="Anticipated (this period)"
              stroke={CHART_COLORS.anticipated}
              strokeWidth={2}
              dot={{ r: 3 }}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <p className="mt-2 text-xs text-zinc-400">
        Anticipated only counts upcoming appointments starting within each bar&apos;s own period — the
        breakdown below can show a larger total, since it includes upcoming appointments in later periods too.
      </p>
    </div>
  );
}

function PeriodTypeButton({ label, active, onClick, disabled }: { label: string; active: boolean; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        active ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
      }`}
    >
      {label}
    </button>
  );
}

function PresetButton({ label, active, onClick, disabled }: { label: string; active: boolean; onClick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={
        (active
          ? 'rounded-full bg-blue-600 px-3 py-1.5 text-xs font-medium text-white'
          : 'rounded-full bg-zinc-100 px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-200') +
        ' disabled:cursor-not-allowed disabled:opacity-50'
      }
    >
      {label}
    </button>
  );
}

function StatCard({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg p-3 ring-1 ring-zinc-200 sm:p-4">
      <div className="text-[10px] font-medium uppercase tracking-wide text-zinc-500 sm:text-xs">{label}</div>
      <div className="mt-1 text-lg font-semibold text-zinc-900 sm:text-2xl">{value}</div>
      {hint && <div className="mt-0.5 text-[10px] text-amber-600 sm:text-xs">{hint}</div>}
    </div>
  );
}

/** Collected + Anticipated (this period) + Anticipated (outside period) = Total, laid out as a
 * literal equation so the relationship between the four money figures is obvious at a glance,
 * rather than four same-looking numbers scattered among unrelated stats. "horizontal" (the top
 * summary) reads left to right with +/= connectors; "stacked" (a single table row/mobile card)
 * reads top to bottom, since there's no room for four side-by-side cards there. Wrapped in its own
 * emerald-tinted card with a title + info icon (BlockTitle) so it reads as one distinct block next
 * to the ROI and Bookings blocks below, not just another row of numbers. */
function MoneyBreakdown({
  row, layout, lang, onExpand,
}: {
  row: MarketingAdsReportPeriod;
  layout: 'horizontal' | 'stacked';
  lang: Language | null;
  /** When given, every term becomes a button that opens the appointment ledger pre-filtered to
   * that figure — the "quick look" popup. Only wired up for the top summary's totals row; the
   * per-period table/mobile cards below don't get it (no per-period ledger scope exists). */
  onExpand?: (filter: LedgerFilter) => void;
}) {
  const total = totalRevenueOf(row);
  const title = <BlockTitle label={t(lang, 'adsRevenueTitle')} info={t(lang, 'adsRevenueInfo')} />;
  if (layout === 'stacked') {
    return (
      <div className="rounded-lg border-l-4 border-emerald-300 bg-emerald-50/40 p-2.5 text-xs ring-1 ring-zinc-200">
        {title}
        <div className="flex items-center justify-between">
          <span className="text-zinc-500">Collected</span>
          <span className="tabular-nums font-medium text-zinc-900">{usdExact(row.revenueCollected)}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.revenueCollectedSplit, usdExact)}</div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (this period)</span>
          <span className="tabular-nums text-zinc-600">{usdExact(row.anticipatedRevenue)}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.anticipatedRevenueSplit, usdExact)}</div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (outside period)</span>
          <span className="tabular-nums text-zinc-600">{usdExact(row.anticipatedRevenueOutsidePeriod)}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.anticipatedRevenueOutsidePeriodSplit, usdExact)}</div>
        <div className="mt-1.5 flex items-center justify-between border-t border-zinc-300 pt-1.5">
          <span className="font-semibold text-emerald-700">= Total</span>
          <span className="tabular-nums font-semibold text-emerald-700">{usdExact(total)}</span>
        </div>
      </div>
    );
  }
  return (
    <section className="rounded-xl border-l-4 border-emerald-300 bg-emerald-50/40 p-3 ring-1 ring-zinc-200 sm:p-4">
      {title}
      <div className="flex flex-wrap items-stretch gap-1.5">
        <MoneyTerm label="Collected" value={usd(row.revenueCollected)} hint={splitLabel(row.revenueCollectedSplit, usd)} onClick={onExpand && (() => onExpand('completed'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (this period)" value={usd(row.anticipatedRevenue)} hint={splitLabel(row.anticipatedRevenueSplit, usd)} onClick={onExpand && (() => onExpand('anticipated-period'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (outside period)" value={usd(row.anticipatedRevenueOutsidePeriod)} hint={splitLabel(row.anticipatedRevenueOutsidePeriodSplit, usd)} onClick={onExpand && (() => onExpand('anticipated-outside'))} />
        <MoneyOperator symbol="=" />
        <MoneyTerm label="Total" value={usd(total)} tone="positive" onClick={onExpand && (() => onExpand('all'))} />
      </div>
    </section>
  );
}

/** Ad spend + Realized ROAS + Total ROAS + ROI% — the same figures the WhatsApp text export
 * groups under "*Ad spend & ROI*", laid out the same equation-adjacent way MoneyBreakdown lays out
 * the money terms (no +/= operators here though — ad spend isn't summed with the ROAS/ROI figures,
 * it's just the input they're all computed from). Ad spend lives here now, not as its own separate
 * stat card, since it's meaningless without the return-on-spend figures next to it. The whole block
 * only renders when there's spend to show a return on — see AdsReportView's `showRoi`. ROI% is
 * colored green/red by sign, since (unlike the two ROAS multiples) it's the one figure here that's
 * meaningfully good or bad. */
function ROIBreakdown({ row, layout, lang }: { row: MarketingAdsReportPeriod; layout: 'horizontal' | 'stacked'; lang: Language | null }) {
  const { realizedRoas, totalRoas, roiPercent } = roiMetricsOf(row);
  const roiTone: 'positive' | 'negative' | undefined =
    roiPercent === null ? undefined : roiPercent >= 0 ? 'positive' : 'negative';
  const roiTextClass = roiTone === 'negative' ? 'text-rose-700' : 'text-emerald-700';
  const title = <BlockTitle label={t(lang, 'adsRoiTitle')} info={t(lang, 'adsRoiInfo')} />;
  const adSpendLabel = (exact: boolean) => `${row.adSpendEstimated ? '~' : ''}${exact ? usdExact(row.adSpend) : usd(row.adSpend)}`;
  if (layout === 'stacked') {
    return (
      <div className="rounded-lg border-l-4 border-blue-300 bg-blue-50/40 p-2.5 text-xs ring-1 ring-zinc-200">
        {title}
        <div className="flex items-center justify-between">
          <span className="text-zinc-500">{t(lang, 'adsAdSpend')}</span>
          <span className="tabular-nums font-medium text-zinc-900">{adSpendLabel(true)}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">Realized ROAS</span>
          <span className="tabular-nums text-zinc-600">{roiLabel(realizedRoas)}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">Total ROAS</span>
          <span className="tabular-nums text-zinc-600">{roiLabel(totalRoas)}</span>
        </div>
        <div className="mt-1.5 flex items-center justify-between border-t border-zinc-300 pt-1.5">
          <span className={`font-semibold ${roiTextClass}`}>ROI</span>
          <span className={`tabular-nums font-semibold ${roiTextClass}`}>{roiPercentLabel(roiPercent)}</span>
        </div>
      </div>
    );
  }
  return (
    <section className="rounded-xl border-l-4 border-blue-300 bg-blue-50/40 p-3 ring-1 ring-zinc-200 sm:p-4">
      {title}
      <div className="flex flex-wrap items-stretch gap-1.5">
        <MoneyTerm label={t(lang, 'adsAdSpend')} value={adSpendLabel(false)} />
        <MoneyTerm label="Realized ROAS" value={roiLabel(realizedRoas)} />
        <MoneyTerm label="Total ROAS" value={roiLabel(totalRoas)} />
        <MoneyTerm label="ROI" value={roiPercentLabel(roiPercent)} tone={roiTone} />
      </div>
    </section>
  );
}

/** Completed + Cancelled + Anticipated = Total bookings — what actually happened to every booking
 * dated in this period, laid out the same equation-style way as MoneyBreakdown/ROIBreakdown so a
 * reader who's used those reads this one the same way. Cancelled is tinted rose once it's
 * non-zero, the one figure here that's meaningfully bad news rather than neutral bookkeeping. */
function BookingsBreakdown({
  row, layout, lang, onExpand,
}: {
  row: MarketingAdsReportPeriod;
  layout: 'horizontal' | 'stacked';
  lang: Language | null;
  onExpand?: (filter: LedgerFilter) => void;
}) {
  const b = bookingsBreakdownOf(row);
  const cancelledLabel = `${b.cancelled}${b.cancelledPercent !== null ? ` (${b.cancelledPercent.toFixed(0)}%)` : ''}`;
  const cancelledTone: 'negative' | undefined = b.cancelled > 0 ? 'negative' : undefined;
  const title = <BlockTitle label={t(lang, 'adsBookingsTitle')} info={t(lang, 'adsBookingsInfo')} />;
  if (layout === 'stacked') {
    return (
      <div className="rounded-lg border-l-4 border-violet-300 bg-violet-50/40 p-2.5 text-xs ring-1 ring-zinc-200">
        {title}
        <div className="flex items-center justify-between">
          <span className="text-zinc-500">Completed</span>
          <span className="tabular-nums font-medium text-zinc-900">{b.completed}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.completedAppointmentsSplit, String)}</div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">Cancelled</span>
          <span className={`tabular-nums ${b.cancelled > 0 ? 'text-rose-600' : 'text-zinc-600'}`}>{cancelledLabel}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (this period)</span>
          <span className="tabular-nums text-zinc-600">{b.anticipated}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.anticipatedAppointmentsSplit, String)}</div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (outside period)</span>
          <span className="tabular-nums text-zinc-600">{b.anticipatedOutsidePeriod}</span>
        </div>
        <div className="text-right text-[10px] text-zinc-400">{splitLabel(row.anticipatedAppointmentsOutsidePeriodSplit, String)}</div>
        <div className="mt-1.5 flex items-center justify-between border-t border-zinc-300 pt-1.5">
          <span className="font-semibold text-zinc-700">= Total bookings</span>
          <span className="tabular-nums font-semibold text-zinc-900">{b.total}</span>
        </div>
      </div>
    );
  }
  return (
    <section className="rounded-xl border-l-4 border-violet-300 bg-violet-50/40 p-3 ring-1 ring-zinc-200 sm:p-4">
      {title}
      <div className="flex flex-wrap items-stretch gap-1.5">
        <MoneyTerm label="Completed" value={String(b.completed)} hint={splitLabel(row.completedAppointmentsSplit, String)} onClick={onExpand && (() => onExpand('completed'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Cancelled" value={cancelledLabel} tone={cancelledTone} onClick={onExpand && (() => onExpand('cancelled-period'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (period)" value={String(b.anticipated)} hint={splitLabel(row.anticipatedAppointmentsSplit, String)} onClick={onExpand && (() => onExpand('anticipated-period'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (outside period)" value={String(b.anticipatedOutsidePeriod)} hint={splitLabel(row.anticipatedAppointmentsOutsidePeriodSplit, String)} onClick={onExpand && (() => onExpand('anticipated-outside'))} />
        <MoneyOperator symbol="=" />
        <MoneyTerm label="Total bookings" value={String(b.total)} onClick={onExpand && (() => onExpand('all'))} />
      </div>
    </section>
  );
}

/** Completed + Cancelled + Anticipated (period) + Anticipated (outside) = Total customers — the
 * same four buckets as BookingsBreakdown, but counting distinct people instead of appointments.
 * Exists because those two questions genuinely differ: "Collected: 6 bookings" earlier in the
 * week and "Anticipated (outside): 6 bookings" for next month can describe the same 3 repeat
 * customers or 12 different ones, and bookings alone can't tell you which. Shares onExpand with
 * BookingsBreakdown (the same underlying ledger filter, just read as "who" instead of "how many
 * visits") — see adsCustomersInfo for the one caveat (a customer in two buckets is counted in
 * both, so the four don't add up to a unique headcount). */
function CustomersBreakdown({
  row, layout, lang, onExpand,
}: {
  row: MarketingAdsReportPeriod;
  layout: 'horizontal' | 'stacked';
  lang: Language | null;
  onExpand?: (filter: LedgerFilter) => void;
}) {
  const c = customersBreakdownOf(row);
  const title = <BlockTitle label={t(lang, 'adsCustomersTitle')} info={t(lang, 'adsCustomersInfo')} />;
  if (layout === 'stacked') {
    return (
      <div className="rounded-lg border-l-4 border-amber-300 bg-amber-50/40 p-2.5 text-xs ring-1 ring-zinc-200">
        {title}
        <div className="flex items-center justify-between">
          <span className="text-zinc-500">Completed</span>
          <span className="tabular-nums font-medium text-zinc-900">{c.completed}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">Cancelled</span>
          <span className={`tabular-nums ${c.cancelled > 0 ? 'text-rose-600' : 'text-zinc-600'}`}>{c.cancelled}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (this period)</span>
          <span className="tabular-nums text-zinc-600">{c.anticipated}</span>
        </div>
        <div className="mt-1 flex items-center justify-between">
          <span className="text-zinc-400">+ Anticipated (outside period)</span>
          <span className="tabular-nums text-zinc-600">{c.anticipatedOutsidePeriod}</span>
        </div>
        <div className="mt-1.5 flex items-center justify-between border-t border-zinc-300 pt-1.5">
          <span className="font-semibold text-zinc-700">= Total customers</span>
          <span className="tabular-nums font-semibold text-zinc-900">{c.total}</span>
        </div>
      </div>
    );
  }
  return (
    <section className="rounded-xl border-l-4 border-amber-300 bg-amber-50/40 p-3 ring-1 ring-zinc-200 sm:p-4">
      {title}
      <div className="flex flex-wrap items-stretch gap-1.5">
        <MoneyTerm label="Completed" value={String(c.completed)} onClick={onExpand && (() => onExpand('completed'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Cancelled" value={String(c.cancelled)} tone={c.cancelled > 0 ? 'negative' : undefined} onClick={onExpand && (() => onExpand('cancelled-period'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (period)" value={String(c.anticipated)} onClick={onExpand && (() => onExpand('anticipated-period'))} />
        <MoneyOperator symbol="+" />
        <MoneyTerm label="Anticipated (outside period)" value={String(c.anticipatedOutsidePeriod)} onClick={onExpand && (() => onExpand('anticipated-outside'))} />
        <MoneyOperator symbol="=" />
        <MoneyTerm label="Total customers" value={String(c.total)} onClick={onExpand && (() => onExpand('all'))} />
      </div>
    </section>
  );
}

/** Small uppercase label + info icon shown at the top of every MoneyBreakdown/ROIBreakdown/
 * BookingsBreakdown/CustomersBreakdown block, in both layouts — gives each block a name and a
 * plain-language explanation (localized, see i18n.ts's ads* keys) instead of leaving a reader to
 * infer what "Collected / Anticipated / Total" as a group is even about. */
function BlockTitle({ label, info }: { label: string; info: string }) {
  return (
    <div className="mb-1.5 flex items-center gap-1">
      <span className="text-[10px] font-semibold uppercase tracking-wide text-zinc-500 sm:text-xs">{label}</span>
      <InfoTooltip text={info} />
    </div>
  );
}

/** Tap-to-toggle (not hover-only, so it works on the phone the owner actually checks this report
 * from) info icon. Closes on blur rather than needing an outside-click listener — tabbing or
 * tapping away from the button already fires blur. */
function InfoTooltip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <span className="relative inline-flex">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        onBlur={() => setOpen(false)}
        aria-label="Info"
        className="flex h-3.5 w-3.5 items-center justify-center rounded-full text-zinc-400 hover:text-zinc-600 focus:outline-none"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-3.5 w-3.5">
          <path
            fillRule="evenodd"
            d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
            clipRule="evenodd"
          />
        </svg>
      </button>
      {open && (
        <span
          role="tooltip"
          className="absolute left-0 top-full z-30 mt-1 w-60 rounded-lg bg-zinc-900 p-2 text-[11px] font-normal normal-case leading-snug text-white shadow-lg"
        >
          {text}
        </span>
      )}
    </span>
  );
}

/** A single figure tile in the Revenue/ROI/Bookings equation rows. When `onClick` is given, the
 * whole tile becomes a button that opens the appointment ledger for that figure — a bigger, easier
 * tap target than a small expand icon would be, with a magnifying-glass hint in the corner and a
 * hover/active state so it reads as interactive rather than just another stat. */
function MoneyTerm({
  label, value, tone, onClick, hint,
}: {
  label: string;
  value: string;
  tone?: 'positive' | 'negative';
  onClick?: () => void;
  /** A small caption under the value — the first-visit/repeat split for terms that have one. */
  hint?: string;
}) {
  const boxClass = tone === 'positive' ? 'bg-emerald-50 ring-emerald-200'
    : tone === 'negative' ? 'bg-rose-50 ring-rose-200'
      : 'ring-zinc-200';
  const textClass = tone === 'positive' ? 'text-emerald-700' : tone === 'negative' ? 'text-rose-700' : 'text-zinc-900';
  const content = (
    <>
      <div className="flex items-start justify-between gap-1">
        <div className="text-[10px] font-medium uppercase tracking-wide text-zinc-500 sm:text-xs">{label}</div>
        {onClick && <SearchIcon className="h-3 w-3 shrink-0 text-zinc-400" />}
      </div>
      <div className={`mt-1 text-lg font-semibold sm:text-2xl ${textClass}`}>{value}</div>
      {hint && <div className="mt-0.5 text-[10px] leading-tight text-zinc-400">{hint}</div>}
    </>
  );
  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className={`rounded-lg p-3 text-left ring-1 transition-[filter] hover:brightness-95 active:brightness-90 sm:p-4 ${boxClass}`}
      >
        {content}
      </button>
    );
  }
  return <div className={`rounded-lg p-3 ring-1 sm:p-4 ${boxClass}`}>{content}</div>;
}

function SearchIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={2} className={className} aria-hidden="true">
      <circle cx="8.5" cy="8.5" r="5.5" />
      <path d="M17 17l-4-4" strokeLinecap="round" />
    </svg>
  );
}

function MoneyOperator({ symbol }: { symbol: string }) {
  return <span className="self-center px-0.5 text-xl font-light text-zinc-300" aria-hidden="true">{symbol}</span>;
}

function AdSpendCell({ row }: { row: MarketingAdsReportPeriod }) {
  if (row.adSpendEstimated) {
    return (
      <span title="Estimated — the entered spend doesn't exactly tile this period, so this is prorated by calendar-day overlap.">
        ~{usdExact(row.adSpend)}
      </span>
    );
  }
  return <span>{usdExact(row.adSpend)}</span>;
}

function InProgressBadge() {
  return (
    <span className="whitespace-nowrap rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-medium text-amber-700">
      In progress
    </span>
  );
}

function CurrentBadge() {
  return (
    <span className="whitespace-nowrap rounded-full bg-blue-600 px-2 py-0.5 text-[10px] font-medium text-white">
      Current
    </span>
  );
}

function PeriodTable({
  periods, periodType, lang, showRoi,
}: {
  periods: MarketingAdsReportPeriod[];
  periodType: MarketingAdsReportData['periodType'];
  lang: Language | null;
  showRoi: boolean;
}) {
  return (
    <>
      {/* Mobile cards */}
      <div className="mt-4 flex flex-col gap-2 sm:hidden">
        {periods.map((row) => {
          const current = isCurrentPeriod(row);
          return (
            <div
              key={row.periodStart}
              className={`rounded-lg p-3 ring-1 ${current ? 'bg-blue-50 ring-blue-200' : 'ring-zinc-200'}`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium">{fmtPeriodLabel(row, periodType)}</span>
                <div className="flex items-center gap-1">
                  {row.monthInProgress && periodType === 'MONTH' && <InProgressBadge />}
                  {current && !row.monthInProgress && <CurrentBadge />}
                </div>
              </div>
              <div className="mt-2 flex flex-col gap-2">
                <MoneyBreakdown row={row} layout="stacked" lang={lang} />
                {showRoi && <ROIBreakdown row={row} layout="stacked" lang={lang} />}
                <BookingsBreakdown row={row} layout="stacked" lang={lang} />
                <CustomersBreakdown row={row} layout="stacked" lang={lang} />
              </div>
            </div>
          );
        })}
      </div>

      {/* Desktop table */}
      <div className="mt-4 hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2" rowSpan={2}>Period</th>
              <th className="border-l border-zinc-200 px-3 py-1.5 text-center" colSpan={4}>{t(lang, 'adsRevenueTitle')}</th>
              {showRoi && (
                <th className="border-l border-zinc-200 px-3 py-1.5 text-center" colSpan={4}>{t(lang, 'adsRoiTitle')}</th>
              )}
              <th className="border-l border-zinc-200 px-3 py-1.5 text-center" colSpan={5}>{t(lang, 'adsBookingsTitle')}</th>
              <th className="border-l border-zinc-200 px-3 py-1.5 text-center" colSpan={5}>{t(lang, 'adsCustomersTitle')}</th>
            </tr>
            <tr>
              <th className="border-l border-zinc-200 px-3 py-2 text-right font-normal normal-case">Collected</th>
              <th
                className="px-3 py-2 text-right font-normal normal-case"
                title="Upcoming appointments starting within this period only."
              >
                Anticipated (period)
              </th>
              <th
                className="px-3 py-2 text-right font-normal normal-case"
                title="Upcoming appointments dated outside this period, booked by exactly the customers first captured by ads within this same period."
              >
                Anticipated (outside period)
              </th>
              <th className="bg-emerald-50 px-3 py-2 text-right font-semibold normal-case text-emerald-700">
                = Total
              </th>
              {showRoi && (
                <>
                  <th className="border-l border-zinc-200 px-3 py-2 text-right font-normal normal-case">
                    {t(lang, 'adsAdSpend')}
                  </th>
                  <th className="px-3 py-2 text-right font-normal normal-case" title="Ad spend against what's actually been collected.">
                    Realized ROAS
                  </th>
                  <th className="px-3 py-2 text-right font-normal normal-case" title="Ad spend against the Total column (Collected + both Anticipated figures).">
                    Total ROAS
                  </th>
                  <th className="px-3 py-2 text-right font-normal normal-case" title="Profit over ad spend, using that same Total.">
                    ROI %
                  </th>
                </>
              )}
              <th className="border-l border-zinc-200 px-3 py-2 text-right font-normal normal-case">Completed</th>
              <th className="px-3 py-2 text-right font-normal normal-case" title="Cancelled by either side, declined, or no-show.">
                Cancelled
              </th>
              <th className="px-3 py-2 text-right font-normal normal-case" title="Upcoming appointments starting within this period only.">
                Anticipated (period)
              </th>
              <th
                className="px-3 py-2 text-right font-normal normal-case"
                title="Upcoming appointments dated outside this period, booked by exactly the customers first captured by ads within this same period."
              >
                Anticipated (outside period)
              </th>
              <th className="bg-zinc-100 px-3 py-2 text-right font-semibold normal-case text-zinc-700">
                = Total
              </th>
              <th className="border-l border-zinc-200 px-3 py-2 text-right font-normal normal-case">Completed</th>
              <th className="px-3 py-2 text-right font-normal normal-case" title="Cancelled by either side, declined, or no-show.">
                Cancelled
              </th>
              <th className="px-3 py-2 text-right font-normal normal-case" title="Upcoming appointments starting within this period only.">
                Anticipated (period)
              </th>
              <th
                className="px-3 py-2 text-right font-normal normal-case"
                title="Upcoming appointments dated outside this period, booked by exactly the customers first captured by ads within this same period."
              >
                Anticipated (outside period)
              </th>
              <th className="bg-amber-50 px-3 py-2 text-right font-semibold normal-case text-amber-700">
                = Total
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {periods.map((row) => {
              const current = isCurrentPeriod(row);
              const { realizedRoas, totalRoas, roiPercent } = roiMetricsOf(row);
              const roiTextClass = roiPercent === null ? 'text-zinc-600' : roiPercent >= 0 ? 'text-emerald-700' : 'text-rose-700';
              const bookings = bookingsBreakdownOf(row);
              const cancelledLabel = `${bookings.cancelled}${bookings.cancelledPercent !== null ? ` (${bookings.cancelledPercent.toFixed(0)}%)` : ''}`;
              const customers = customersBreakdownOf(row);
              return (
                <tr key={row.periodStart} className={current ? 'bg-blue-50' : 'hover:bg-zinc-50'}>
                  <td className="px-3 py-2 font-medium">
                    <div className="flex items-center gap-2">
                      {fmtPeriodLabel(row, periodType)}
                      {row.monthInProgress && periodType === 'MONTH' && <InProgressBadge />}
                      {current && !row.monthInProgress && <CurrentBadge />}
                    </div>
                  </td>
                  <td className="border-l border-zinc-100 px-3 py-2 text-right tabular-nums text-zinc-600">
                    {usdExact(row.revenueCollected)}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.revenueCollectedSplit, usdExact)}</div>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">
                    {usdExact(row.anticipatedRevenue)}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.anticipatedRevenueSplit, usdExact)}</div>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">
                    {usdExact(row.anticipatedRevenueOutsidePeriod)}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.anticipatedRevenueOutsidePeriodSplit, usdExact)}</div>
                  </td>
                  <td className={`px-3 py-2 text-right tabular-nums font-semibold text-emerald-700 ${current ? 'bg-emerald-50/60' : 'bg-emerald-50/40'}`}>
                    {usdExact(totalRevenueOf(row))}
                  </td>
                  {showRoi && (
                    <>
                      <td className="border-l border-zinc-100 px-3 py-2 text-right tabular-nums text-zinc-600"><AdSpendCell row={row} /></td>
                      <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{roiLabel(realizedRoas)}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{roiLabel(totalRoas)}</td>
                      <td className={`px-3 py-2 text-right tabular-nums font-semibold ${roiTextClass}`}>{roiPercentLabel(roiPercent)}</td>
                    </>
                  )}
                  <td className="border-l border-zinc-100 px-3 py-2 text-right tabular-nums text-zinc-600">
                    {bookings.completed}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.completedAppointmentsSplit, String)}</div>
                  </td>
                  <td className={`px-3 py-2 text-right tabular-nums ${bookings.cancelled > 0 ? 'text-rose-600' : 'text-zinc-600'}`}>{cancelledLabel}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">
                    {bookings.anticipated}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.anticipatedAppointmentsSplit, String)}</div>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">
                    {bookings.anticipatedOutsidePeriod}
                    <div className="text-[10px] font-normal text-zinc-400">{splitLabel(row.anticipatedAppointmentsOutsidePeriodSplit, String)}</div>
                  </td>
                  <td className={`px-3 py-2 text-right tabular-nums font-semibold text-zinc-700 ${current ? 'bg-zinc-100' : 'bg-zinc-50'}`}>
                    {bookings.total}
                  </td>
                  <td className="border-l border-zinc-100 px-3 py-2 text-right tabular-nums text-zinc-600">{customers.completed}</td>
                  <td className={`px-3 py-2 text-right tabular-nums ${customers.cancelled > 0 ? 'text-rose-600' : 'text-zinc-600'}`}>{customers.cancelled}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{customers.anticipated}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{customers.anticipatedOutsidePeriod}</td>
                  <td className={`px-3 py-2 text-right tabular-nums font-semibold text-amber-700 ${current ? 'bg-amber-50/60' : 'bg-amber-50/40'}`}>
                    {customers.total}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

// --- Ad-spend entry form ---

function AdSpendEntryForm({ slug }: { slug?: string }) {
  const [pages, setPages] = useState<MarketingLandingPage[]>([]);
  const [page, setPage] = useState(slug ?? DEFAULT_SLUG);
  // Adjusting state during render (not inside an effect) on a prop change — React's own
  // documented pattern — so switching the shared page selector resets which page's spend this
  // form targets.
  const [prevSlug, setPrevSlug] = useState(slug);
  if (slug !== prevSlug) {
    setPrevSlug(slug);
    setPage(slug ?? DEFAULT_SLUG);
  }
  const [preset, setPreset] = useState<'week' | 'month' | 'mtd' | 'custom'>('week');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [entries, setEntries] = useState<AdSpendEntry[]>([]);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState('');

  useEffect(() => {
    let cancelled = false;
    api.getMarketingPages().then((p) => { if (!cancelled) setPages(p); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  async function loadEntries(forPage: string) {
    try {
      const result = await api.listAdSpendEntries(forPage);
      setEntries(result);
    } catch {
      // Non-critical — the form itself still works without the recent-entries list.
    }
  }

  useEffect(() => {
    let cancelled = false;
    api.listAdSpendEntries(page).then((result) => { if (!cancelled) setEntries(result); }).catch(() => {});
    return () => { cancelled = true; };
  }, [page]);

  function selectPreset(p: 'week' | 'month' | 'mtd' | 'custom') {
    setPreset(p);
    if (p === 'week') {
      const r = thisWeekRange(); setFrom(r.from); setTo(r.to);
    } else if (p === 'month') {
      const r = thisMonthRange(); setFrom(r.from); setTo(r.to);
    } else if (p === 'mtd') {
      const r = monthToDateSoFarRange(); setFrom(r.from); setTo(r.to);
    }
    // 'custom' leaves from/to for the owner to pick below.
  }

  async function submit() {
    const value = Number(amount);
    if (!from || !to) { setError('Pick a period.'); return; }
    if (!Number.isFinite(value) || value < 0) { setError('Enter a valid, non-negative amount.'); return; }
    setBusy(true);
    setError('');
    try {
      await api.createAdSpendEntry(page, from, to, value);
      setAmount('');
      setSavedAt(Date.now());
      void loadEntries(page);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save ad spend.');
    } finally {
      setBusy(false);
    }
  }

  async function saveEdit(id: number, editFrom: string, editTo: string, editAmount: number) {
    setRowBusyId(id);
    setRowError('');
    try {
      await api.updateAdSpendEntry(id, editFrom, editTo, editAmount);
      setEditingId(null);
      void loadEntries(page);
    } catch (e) {
      setRowError(e instanceof Error ? e.message : 'Failed to save changes.');
    } finally {
      setRowBusyId(null);
    }
  }

  async function deleteEntry(id: number) {
    if (!window.confirm('Delete this ad spend entry? This can\'t be undone.')) return;
    setRowBusyId(id);
    setRowError('');
    try {
      await api.deleteAdSpendEntry(id);
      void loadEntries(page);
    } catch (e) {
      setRowError(e instanceof Error ? e.message : 'Failed to delete entry.');
    } finally {
      setRowBusyId(null);
    }
  }

  return (
    <div className="mt-8 border-t border-zinc-100 pt-8">
      <h2 className="text-sm font-medium text-zinc-500">Enter ad spend</h2>
      <p className="mt-1 text-xs text-zinc-400">
        Fixing an outright mistake? Edit or delete the entry below instead — save a new row here only
        for a genuine revision you want kept in the history.
      </p>

      <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        {pages.length > 1 && (
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">Page</span>
            <select
              value={page}
              onChange={(e) => setPage(e.target.value)}
              className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
            >
              {pages.map((p) => (
                <option key={p.slug} value={p.slug}>{p.name}</option>
              ))}
            </select>
          </label>
        )}

        <div className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Period</span>
          <div className="flex flex-wrap gap-1 rounded-lg bg-zinc-100 p-1">
            <PeriodTypeButton label="This week" active={preset === 'week'} onClick={() => selectPreset('week')} />
            <PeriodTypeButton label="This month" active={preset === 'month'} onClick={() => selectPreset('month')} />
            <PeriodTypeButton label="Month-to-date" active={preset === 'mtd'} onClick={() => selectPreset('mtd')} />
            <PeriodTypeButton label="Custom" active={preset === 'custom'} onClick={() => selectPreset('custom')} />
          </div>
        </div>

        {preset === 'custom' && (
          <>
            <label className="flex flex-col gap-1 text-xs">
              <span className="font-medium text-zinc-500">From</span>
              <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)}
                className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span className="font-medium text-zinc-500">To</span>
              <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)}
                className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
            </label>
          </>
        )}

        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Amount</span>
          <div className="flex items-center gap-1">
            <span className="text-zinc-400">$</span>
            <input
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="w-28 rounded border border-zinc-300 px-2 py-1.5 text-sm"
            />
          </div>
        </label>

        <button
          type="button"
          disabled={busy}
          onClick={submit}
          className="rounded bg-zinc-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {busy ? 'Saving…' : 'Save'}
        </button>
        {savedAt !== null && !busy && <span className="text-xs text-emerald-600">Saved</span>}
      </div>
      {error ? <p className="mt-2 text-sm text-red-600">{error}</p> : null}
      {from && to && <p className="mt-2 text-xs text-zinc-400">{fmtDateRange(from, to)}</p>}

      {entries.length > 0 && (
        <div className="mt-4">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Recent entries — {page}</h3>
          {rowError ? <p className="mt-1 text-xs text-red-600">{rowError}</p> : null}
          <div className="mt-2 flex flex-col gap-1 text-xs text-zinc-600">
            {entries.slice(0, 8).map((e) => (
              <EntryRow
                key={e.id}
                entry={e}
                editing={editingId === e.id}
                busy={rowBusyId === e.id}
                onEdit={() => { setEditingId(e.id); setRowError(''); }}
                onCancelEdit={() => setEditingId(null)}
                onSaveEdit={(f, t, amt) => saveEdit(e.id, f, t, amt)}
                onDelete={() => deleteEntry(e.id)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** One "Recent entries" row — a read-only line by default, switching to an inline edit form (period
 * + amount, same fields as the create form above) when its Edit button is clicked. */
function EntryRow({
  entry, editing, busy, onEdit, onCancelEdit, onSaveEdit, onDelete,
}: {
  entry: AdSpendEntry;
  editing: boolean;
  busy: boolean;
  onEdit: () => void;
  onCancelEdit: () => void;
  onSaveEdit: (from: string, to: string, amount: number) => void;
  onDelete: () => void;
}) {
  const [editFrom, setEditFrom] = useState(entry.periodStart);
  const [editTo, setEditTo] = useState(entry.periodEnd);
  const [editAmount, setEditAmount] = useState(String(entry.amount));

  if (!editing) {
    return (
      <div className="flex items-center justify-between gap-2 rounded px-2 py-1 ring-1 ring-zinc-100">
        <span>{fmtDateRange(entry.periodStart, entry.periodEnd)}</span>
        <div className="flex items-center gap-2">
          <span className="font-medium tabular-nums">{usdExact(entry.amount)}</span>
          <button type="button" onClick={onEdit} className="text-blue-600 hover:underline">Edit</button>
          <button type="button" onClick={onDelete} disabled={busy} className="text-red-600 hover:underline disabled:opacity-50">
            Delete
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-end gap-2 rounded px-2 py-1.5 ring-1 ring-blue-200">
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">From</span>
        <input type="date" value={editFrom} max={editTo} onChange={(e) => setEditFrom(e.target.value)}
          className="rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">To</span>
        <input type="date" value={editTo} min={editFrom} onChange={(e) => setEditTo(e.target.value)}
          className="rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">Amount</span>
        <input type="number" min="0" step="0.01" inputMode="decimal" value={editAmount}
          onChange={(e) => setEditAmount(e.target.value)} className="w-24 rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <button
        type="button"
        disabled={busy}
        onClick={() => onSaveEdit(editFrom, editTo, Number(editAmount))}
        className="rounded bg-zinc-800 px-2 py-1 font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
      >
        {busy ? 'Saving…' : 'Save'}
      </button>
      <button type="button" disabled={busy} onClick={onCancelEdit} className="text-zinc-500 hover:underline disabled:opacity-50">
        Cancel
      </button>
    </div>
  );
}

// --- Breakdown drill-down (ported from the now-removed AnalyticsView.tsx, per design.md D6) ---

type SegmentKey = 'all' | 'fresh' | 'returning';

/** Per-customer "expand to see appointments/submissions" state shared by every LedgerList row —
 * the same Square customer can appear under more than one ledger category, and expanding it under
 * one should show it already-expanded (and already-fetched, not re-fetched) under another. Fetched
 * lazily, one customer at a time, only on the owner's own click (design.md's "fetch on click"
 * decision) — the breakdown's own load stays fast regardless of how many customers are in range.
 */
interface HistoryExpandState {
  isAppointmentsOpen(rowKey: string): boolean;
  isSubmissionsOpen(rowKey: string): boolean;
  isLoading(customerId: string): boolean;
  history(customerId: string): MarketingCustomerHistory | undefined;
  toggleAppointments(rowKey: string, customerId: string): void;
  toggleSubmissions(rowKey: string, customerId: string): void;
}

function useCustomerHistoryExpand(): HistoryExpandState {
  // Expand/collapse is keyed by the row's own unique key, not the customer id — the same Square
  // customer can have more than one row (e.g. a genuine "completed" appointment and a separate,
  // unrelated "anticipated" one), and each row's own toggle should be independent. Expanding one
  // row must never make a *different* row for the same person also render as open — that bug
  // (found investigating Ashanti Williamson always showing a "$85 cash note" line under
  // Anticipated (outside) once her Collected row had been expanded) came from keying this by
  // customerId, which every row for that person shares.
  const [expandedAppointments, setExpandedAppointments] = useState<Set<string>>(new Set());
  const [expandedSubmissions, setExpandedSubmissions] = useState<Set<string>>(new Set());
  // The fetched history itself is still fine to key (and cache) by customer id and share across
  // that person's rows — it's the same real data regardless of which row asked for it, so this
  // avoids re-fetching it once per row.
  const [historyByCustomer, setHistoryByCustomer] = useState<Map<string, MarketingCustomerHistory>>(new Map());
  const [loadingIds, setLoadingIds] = useState<Set<string>>(new Set());

  function ensureLoaded(customerId: string) {
    if (historyByCustomer.has(customerId) || loadingIds.has(customerId)) return;
    setLoadingIds((prev) => new Set(prev).add(customerId));
    api.getMarketingCustomerHistory(customerId)
      .then((result) => setHistoryByCustomer((prev) => new Map(prev).set(customerId, result)))
      // Best-effort: an empty history lets the toggle settle into "No appointments/submissions"
      // rather than spinning forever if this one lookup fails.
      .catch(() => setHistoryByCustomer((prev) => new Map(prev).set(customerId, { submissions: [], appointments: [] })))
      .finally(() => setLoadingIds((prev) => { const next = new Set(prev); next.delete(customerId); return next; }));
  }

  function toggle(
    set: Set<string>, setSet: (updater: (s: Set<string>) => Set<string>) => void, rowKey: string, customerId: string,
  ) {
    const opening = !set.has(rowKey);
    setSet((prev) => {
      const next = new Set(prev);
      if (next.has(rowKey)) next.delete(rowKey);
      else next.add(rowKey);
      return next;
    });
    if (opening) ensureLoaded(customerId);
  }

  return {
    isAppointmentsOpen: (rowKey) => expandedAppointments.has(rowKey),
    isSubmissionsOpen: (rowKey) => expandedSubmissions.has(rowKey),
    isLoading: (id) => loadingIds.has(id),
    history: (id) => historyByCustomer.get(id),
    toggleAppointments: (rowKey, customerId) => toggle(expandedAppointments, setExpandedAppointments, rowKey, customerId),
    toggleSubmissions: (rowKey, customerId) => toggle(expandedSubmissions, setExpandedSubmissions, rowKey, customerId),
  };
}

/** The expand affordance itself — shown under a completed/upcoming row. Three states: not yet
 * clicked (plain links, count unknown), loading (spinner), loaded (real HistoryToggle counts,
 * matching ContactsTable's exact convention including its "no history" plain-text fallback). */
function CustomerHistoryExpand({
  rowKey, customerId, expand,
}: {
  rowKey: string;
  customerId: string;
  expand: HistoryExpandState;
}) {
  const appointmentsOpen = expand.isAppointmentsOpen(rowKey);
  const submissionsOpen = expand.isSubmissionsOpen(rowKey);
  const hist = expand.history(customerId);
  const loading = expand.isLoading(customerId);

  return (
    <div className="mt-2 flex flex-col gap-2 border-t border-zinc-100 pt-2">
      <div className="flex flex-wrap items-center gap-3">
        {hist ? (
          <>
            <HistoryToggle label="Appointments" count={hist.appointments.length} open={appointmentsOpen} onClick={() => expand.toggleAppointments(rowKey, customerId)} />
            <HistoryToggle label="Submissions" count={hist.submissions.length} open={submissionsOpen} onClick={() => expand.toggleSubmissions(rowKey, customerId)} />
          </>
        ) : loading ? (
          <span className="inline-flex items-center gap-1.5 text-xs text-zinc-400">
            <Spinner className="h-3 w-3" /> Loading history…
          </span>
        ) : (
          <>
            <button type="button" onClick={() => expand.toggleAppointments(rowKey, customerId)} className="text-xs font-medium text-blue-600 hover:underline">
              Appointments
            </button>
            <button type="button" onClick={() => expand.toggleSubmissions(rowKey, customerId)} className="text-xs font-medium text-blue-600 hover:underline">
              Submissions
            </button>
          </>
        )}
      </div>
      {hist && (appointmentsOpen || submissionsOpen) && (
        <div className="flex flex-col gap-3">
          {appointmentsOpen && (
            <div>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Appointment History</h4>
              <AppointmentHistoryList appointments={hist.appointments} />
            </div>
          )}
          {submissionsOpen && (
            <div>
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Submission History</h4>
              <SubmissionHistoryList submissions={hist.submissions} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** The deep-dive view ("View breakdown") — every appointment for the range shown above, in one
 * list, filterable by both customer segment (SegmentTabs, existing) and ledger category
 * (LedgerFilterTabs, new) at once. `data`/`loading`/`error` come from AdsReportView's shared
 * useAnalyticsBreakdown — not fetched here — so this and the per-figure popup below always agree
 * on the exact same snapshot and never double the underlying live Square sweep.
 */
function BreakdownDrilldown({
  from, to, data, loading, error,
}: {
  from: string;
  to: string;
  data: MarketingAnalyticsData | null;
  loading: boolean;
  error: string;
}) {
  const [segment, setSegment] = useState<SegmentKey>('all');
  const historyExpand = useCustomerHistoryExpand();

  if (loading && !data) {
    return (
      <div className="mt-4 flex items-center gap-2 text-sm text-zinc-500">
        <Spinner className="h-4 w-4" /> Loading breakdown…
      </div>
    );
  }
  if (error) return <p className="mt-4 text-sm text-red-600">{error}</p>;
  if (!data) return null;

  const activeSegment: MarketingAnalyticsSegment = data[segment];
  const rows = buildLedgerRows(data).filter(
    (r) => segment === 'all' || (segment === 'fresh' ? r.freshFromAds : !r.freshFromAds),
  );

  return (
    <div className="mt-4">
      <SegmentTabs segment={segment} onChange={setSegment} />

      <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatCard label="Customers" value={activeSegment.customerCount.toLocaleString()} />
        <StatCard label="Services" value={activeSegment.serviceCount.toLocaleString()} />
        <StatCard label="Gross revenue" value={usd(activeSegment.grossRevenue)} />
      </div>

      <div className="mt-6">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-sm font-medium text-zinc-500">Every appointment for {segmentLabel(segment).toLowerCase()}</h3>
          <span className="text-xs text-zinc-400">{fmtDateRange(from, to)} — collected, anticipated, and cancelled, all in one place</span>
        </div>
        <AppointmentLedger rows={rows} initialFilter="all" historyExpand={historyExpand} />
      </div>
    </div>
  );
}

/** Reusable filterable list, used both inline ("View breakdown") and inside LedgerModal (the
 * per-figure popup). Owns which category is active; the caller decides which rows are even in
 * play (BreakdownDrilldown pre-filters by customer segment; the popup doesn't need to).
 */
function AppointmentLedger({
  rows, initialFilter, historyExpand,
}: {
  rows: LedgerRow[];
  initialFilter: LedgerFilter;
  historyExpand: HistoryExpandState;
}) {
  const [filter, setFilter] = useState<LedgerFilter>(initialFilter);
  // Reset to whatever category the popup/section was opened with — same "adjust state during
  // render on a prop change" pattern this file already uses (AdsReportView's prevInitialData,
  // AdSpendEntryForm's prevSlug) — not an effect, so there's no one-frame flash of the old filter.
  const [prevInitialFilter, setPrevInitialFilter] = useState(initialFilter);
  if (initialFilter !== prevInitialFilter) {
    setPrevInitialFilter(initialFilter);
    setFilter(initialFilter);
  }

  const counts = useMemo(() => countLedgerRows(rows), [rows]);
  const filtered = filter === 'all' ? rows : rows.filter((r) => r.category === filter);

  // Only meaningful for a single, homogeneous category — mixing Collected with Anticipated/
  // Cancelled amounts under one channel breakdown would conflate real money with estimates.
  const byChannel = filter === 'completed'
    ? filtered.reduce<Record<string, number>>((acc, r) => {
      if (r.paymentChannel) acc[r.paymentChannel] = (acc[r.paymentChannel] ?? 0) + r.amount;
      return acc;
    }, {})
    : null;

  return (
    <div>
      <div className="mt-3">
        <LedgerFilterTabs filter={filter} onChange={setFilter} counts={counts} />
      </div>
      {byChannel && Object.keys(byChannel).length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {Object.entries(byChannel).map(([channel, amount]) => (
            <ChannelBreakdownBadge key={channel} channel={channel} amount={amount} />
          ))}
        </div>
      )}
      {filtered.length === 0 ? (
        <div className="mt-3 rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500">
          Nothing here for this filter.
        </div>
      ) : (
        <LedgerList rows={filtered} historyExpand={historyExpand} />
      )}
    </div>
  );
}

function LedgerFilterTabs({
  filter, onChange, counts,
}: {
  filter: LedgerFilter;
  onChange: (f: LedgerFilter) => void;
  counts: Record<LedgerFilter, number>;
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {LEDGER_FILTERS.map((f) => {
        const active = filter === f;
        const count = counts[f];
        return (
          <button
            key={f}
            type="button"
            onClick={() => onChange(f)}
            disabled={count === 0 && f !== 'all'}
            className={`rounded-full px-3 py-1.5 text-xs font-medium ring-1 ring-inset transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${
              active
                ? 'bg-zinc-900 text-white ring-zinc-900'
                : 'bg-white text-zinc-600 ring-zinc-200 hover:bg-zinc-50'
            }`}
          >
            {LEDGER_FILTER_LABELS[f]} · {count}
          </button>
        );
      })}
    </div>
  );
}

const LEDGER_CATEGORY_STYLES: Record<Exclude<LedgerFilter, 'all'>, string> = {
  completed: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  'anticipated-period': 'bg-violet-50 text-violet-700 ring-violet-200',
  'anticipated-outside': 'bg-violet-50/60 text-violet-500 ring-violet-100',
  'cancelled-period': 'bg-rose-50 text-rose-700 ring-rose-200',
  'cancelled-outside': 'bg-rose-50/60 text-rose-500 ring-rose-100',
};

function LedgerCategoryBadge({ category }: { category: LedgerRow['category'] }) {
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${LEDGER_CATEGORY_STYLES[category]}`}>
      {LEDGER_FILTER_LABELS[category]}
    </span>
  );
}

/** One amount cell — a real collected total shown plainly, a catalog estimate prefixed "~" (the
 * same convention AdSpendCell already uses for an estimated ad-spend figure), so it's never
 * ambiguous whether a $ figure here is money in hand or a still-hypothetical number. */
function LedgerAmount({ row }: { row: LedgerRow }) {
  return <>{row.amountKind === 'estimate' ? '~' : ''}{usdExact(row.amount)}</>;
}

function LedgerList({ rows, historyExpand }: { rows: LedgerRow[]; historyExpand: HistoryExpandState }) {
  return (
    <>
      <div className="mt-3 flex flex-col gap-2 sm:hidden">
        {rows.map((r) => (
          <div key={r.key} className="rounded-lg p-3 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{r.customerName}</span>
              <FreshBadge fresh={r.freshFromAds} />
            </div>
            <div className="mt-1 flex items-center justify-between gap-2 text-sm text-zinc-600">
              <span>{r.serviceName}</span>
              <span className="font-medium tabular-nums"><LedgerAmount row={r} /></span>
            </div>
            <div className="mt-1 flex flex-wrap items-center justify-between gap-2">
              <span className="text-xs text-zinc-400">{r.dateLabel}</span>
              <div className="flex flex-wrap items-center gap-1.5">
                {r.paymentChannel && <PaymentChannelBadge channel={r.paymentChannel} />}
                {r.cancellationStatus && (
                  <span className="whitespace-nowrap text-xs text-zinc-400">{CANCELLATION_STATUS_LABELS[r.cancellationStatus]}</span>
                )}
                <LedgerCategoryBadge category={r.category} />
              </div>
            </div>
            <CustomerHistoryExpand rowKey={r.key} customerId={r.customerId} expand={historyExpand} />
          </div>
        ))}
      </div>

      <div className="mt-3 hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Customer</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">Date</th>
              <th className="px-3 py-2 text-right">Amount</th>
              <th className="px-3 py-2">Detail</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Source</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {rows.map((r) => (
              <Fragment key={r.key}>
                <tr className="hover:bg-zinc-50">
                  <td className="px-3 py-2 font-medium">{r.customerName}</td>
                  <td className="px-3 py-2 text-zinc-600">{r.serviceName}</td>
                  <td className="px-3 py-2 text-zinc-600">{r.dateLabel}</td>
                  <td className="px-3 py-2 text-right tabular-nums"><LedgerAmount row={r} /></td>
                  <td className="px-3 py-2">
                    {r.paymentChannel && <PaymentChannelBadge channel={r.paymentChannel} />}
                    {r.cancellationStatus && (
                      <span className="whitespace-nowrap text-xs text-zinc-400">{CANCELLATION_STATUS_LABELS[r.cancellationStatus]}</span>
                    )}
                  </td>
                  <td className="px-3 py-2"><LedgerCategoryBadge category={r.category} /></td>
                  <td className="px-3 py-2"><FreshBadge fresh={r.freshFromAds} /></td>
                </tr>
                <tr className="bg-zinc-50/50">
                  <td colSpan={7} className="px-3 pb-2">
                    <CustomerHistoryExpand rowKey={r.key} customerId={r.customerId} expand={historyExpand} />
                  </td>
                </tr>
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** The "quick look" popup — opened by clicking a figure in the top summary's Revenue/Bookings
 * blocks (see MoneyTerm's onClick). Deliberately no segment tabs here (that's what makes this the
 * fast path vs. "View breakdown" below it) — just the one category the reader clicked into,
 * with the rest of the ledger's own filter chips still available if they want to look around
 * without leaving the popup. Escape or clicking the backdrop closes it. */
function LedgerModal({
  filter, onClose, data, loading, error,
}: {
  filter: LedgerFilter;
  onClose: () => void;
  data: MarketingAnalyticsData | null;
  loading: boolean;
  error: string;
}) {
  const historyExpand = useCustomerHistoryExpand();

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-4 pt-10 sm:pt-16"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        className="w-full max-w-3xl rounded-xl bg-white p-4 shadow-xl sm:p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-base font-semibold text-zinc-900">{LEDGER_FILTER_LABELS[filter]}</h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-full p-1.5 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600"
          >
            <CloseIcon className="h-4 w-4" />
          </button>
        </div>
        {loading && !data && (
          <div className="mt-4 flex items-center gap-2 text-sm text-zinc-500">
            <Spinner className="h-4 w-4" /> Loading…
          </div>
        )}
        {error && <p className="mt-4 text-sm text-red-600">{error}</p>}
        {data && <AppointmentLedger rows={buildLedgerRows(data)} initialFilter={filter} historyExpand={historyExpand} />}
      </div>
    </div>
  );
}

function CloseIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth={2} className={className} aria-hidden="true">
      <path d="M5 5l10 10M15 5L5 15" strokeLinecap="round" />
    </svg>
  );
}

function segmentLabel(key: SegmentKey): string {
  return key === 'fresh' ? 'new customers' : key === 'returning' ? 'returning customers' : 'ads customers';
}

function SegmentTabs({ segment, onChange }: { segment: SegmentKey; onChange: (s: SegmentKey) => void }) {
  const tabs: { key: SegmentKey; label: string; hint: string }[] = [
    { key: 'all', label: 'All', hint: 'Every ads-attributed customer' },
    { key: 'fresh', label: 'New to Square', hint: 'The ad brought in a customer with no prior history' },
    { key: 'returning', label: 'Returning', hint: 'Already existed in Square, came back via an ad' },
  ];
  return (
    <div className="inline-flex w-full flex-wrap gap-1 rounded-lg bg-zinc-100 p-1 sm:w-auto">
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          title={t.hint}
          onClick={() => onChange(t.key)}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors sm:flex-none ${
            segment === t.key ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
          }`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

function FreshBadge({ fresh }: { fresh: boolean }) {
  return (
    <span
      className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${
        fresh ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-zinc-100 text-zinc-600 ring-zinc-200'
      }`}
    >
      {fresh ? 'New to Square' : 'Returning'}
    </span>
  );
}

function ChannelBreakdownBadge({ channel, amount }: { channel: string; amount: number }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-inset ring-zinc-200">
      {PAYMENT_CHANNEL_LABELS[channel] ?? channel}: {usdExact(amount)}
    </span>
  );
}

function fmtDay(isoDay: string): string {
  const [y, m, d] = isoDay.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
}

function fmtAppointment(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const isToday = d.toDateString() === today.toDateString();
  const day = isToday ? 'Today' : d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  return `${day} · ${time}`;
}
