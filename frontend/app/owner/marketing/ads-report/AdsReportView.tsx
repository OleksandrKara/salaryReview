'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
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
import type {
  AdSpendEntry,
  MarketingAdsReportData,
  MarketingAdsReportPeriod,
  MarketingAnalyticsData,
  MarketingAnalyticsSegment,
  MarketingCompletedAppointment,
  MarketingCustomerHistory,
  MarketingLandingPage,
  MarketingUpcomingAppointment,
  TrafficSourceKey,
} from '../../../lib/types';
import { Spinner } from '../../../components/Spinner';
import TrafficSourceFilter, { ADS_ONLY_SOURCES } from '../TrafficSourceFilter';
import { AppointmentHistoryList, HistoryToggle, PAYMENT_CHANNEL_LABELS, PaymentChannelBadge, SubmissionHistoryList } from '../ContactHistory';

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

function lastNWeeksRange(n: number): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - (n * 7 - 1));
  return { from: isoDate(from), to: isoDate(to) };
}

function lastNMonthsRange(n: number): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getFullYear(), to.getMonth() - (n - 1), 1);
  return { from: isoDate(from), to: isoDate(to) };
}

function mondayOnOrBefore(d: Date): Date {
  const day = d.getDay(); // 0 = Sunday .. 6 = Saturday
  const diff = day === 0 ? 6 : day - 1; // days since the preceding Monday
  const monday = new Date(d);
  monday.setDate(d.getDate() - diff);
  return monday;
}

function thisWeekRange(): { from: string; to: string } {
  const monday = mondayOnOrBefore(new Date());
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  return { from: isoDate(monday), to: isoDate(sunday) };
}

function thisMonthRange(): { from: string; to: string } {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  const to = new Date(today.getFullYear(), today.getMonth() + 1, 0);
  return { from: isoDate(from), to: isoDate(to) };
}

function monthToDateSoFarRange(): { from: string; to: string } {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  return { from: isoDate(from), to: isoDate(today) };
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
  const today = isoDate(new Date());
  return today >= row.periodStart && today <= row.periodEnd;
}

function roiMultiple(spend: number, revenue: number): number | null {
  return spend > 0 ? revenue / spend : null;
}

function roiLabel(roi: number | null): string {
  return roi === null ? '—' : `${roi.toFixed(1)}x`;
}

// Money (collected/anticipated/total), ROI (realized vs total ROAS/ROI%), and customers created
// vs. follow-ups — everything MarketingAdsReportDto.PeriodRow actually carries today. Visits/
// clicks/leads/unbooked (the Funnel-sourced part of the manual reports this mirrors) aren't wired
// up per-period yet — see openspec/changes/ads-report-consolidation/design.md D7's scope note —
// so they're left out here rather than faked.
function formatWhatsAppReport(row: MarketingAdsReportPeriod, periodType: MarketingAdsReportData['periodType'], slug?: string): string {
  const totalRevenue = row.revenueCollected + row.anticipatedRevenue;
  const realizedRoas = roiMultiple(row.adSpend, row.revenueCollected);
  const totalRoas = roiMultiple(row.adSpend, totalRevenue);
  const roiPercent = row.adSpend > 0 ? ((totalRevenue - row.adSpend) / row.adSpend) * 100 : null;
  const totalCustomers = row.customersCreated + row.customersFollowedUp;
  const costPerCustomer = totalCustomers > 0 && row.adSpend > 0 ? row.adSpend / totalCustomers : null;

  const lines: string[] = [];
  lines.push(`*Ads Report${slug ? ` — ${slug}` : ''}*`);
  lines.push(`${fmtPeriodLabel(row, periodType)}${row.monthInProgress ? ' (in progress)' : ''}`);
  lines.push('');
  lines.push('*Money*');
  lines.push(`Collected: ${usdExact(row.revenueCollected)}`);
  lines.push(`Anticipated (this period only): ${usdExact(row.anticipatedRevenue)}`);
  lines.push(`Total: ${usdExact(totalRevenue)}`);
  lines.push('');
  lines.push('*Ad spend & ROI*');
  lines.push(`Spend: ${row.adSpendEstimated ? '~' : ''}${usdExact(row.adSpend)}`);
  lines.push(`Realized ROAS: ${roiLabel(realizedRoas)}`);
  lines.push(`Total ROAS: ${roiLabel(totalRoas)}`);
  if (roiPercent !== null) lines.push(`ROI: ${roiPercent >= 0 ? '+' : ''}${roiPercent.toFixed(0)}%`);
  lines.push('');
  lines.push('*Customers*');
  lines.push(`New: ${row.customersCreated}`);
  lines.push(`Booked ahead by new customers (any future date): ${usdExact(row.newCustomerBookedAhead)}`);
  lines.push(`Follow-up (booked by manager): ${row.customersFollowedUp}`);
  lines.push(`Completed appts: ${row.completedAppointments}`);
  if (costPerCustomer !== null) lines.push(`Cost per customer: ${usdExact(costPerCustomer)}`);
  return lines.join('\n');
}

type PeriodType = 'week' | 'month' | 'mtd' | 'custom';
type ViewMode = 'table' | 'text' | 'chart';

const WEEK_PRESETS = [4, 8, 12];
const MONTH_PRESETS = [3, 6, 12];
const DEFAULT_SLUG = 'mani';

export default function AdsReportView({ initialData, slug }: { initialData: MarketingAdsReportData; slug?: string }) {
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

  const [periodType, setPeriodType] = useState<PeriodType>('mtd');
  const [rangeCount, setRangeCount] = useState(8);
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
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

  function selectPeriodType(pt: PeriodType) {
    setPeriodType(pt);
    if (view === 'chart' && pt !== 'month') setView('table');
    if (pt === 'week' || pt === 'month') {
      const defaultN = pt === 'week' ? 8 : 6;
      setRangeCount(defaultN);
      const range = computeRange(pt, defaultN);
      void load(pt, sources, range.from, range.to);
    } else if (pt === 'mtd') {
      void load('mtd', sources);
    }
    // 'custom' just switches the control panel — loading waits for Apply below.
  }

  function selectRangePreset(n: number) {
    setRangeCount(n);
    const range = computeRange(periodType as 'week' | 'month', n);
    void load(periodType, sources, range.from, range.to);
  }

  function applyCustomRange() {
    if (!customFrom || !customTo) return;
    void load('custom', sources, customFrom, customTo);
  }

  function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    if (periodType === 'week' || periodType === 'month') {
      const range = computeRange(periodType, rangeCount);
      void load(periodType, next, range.from, range.to);
    } else if (periodType === 'mtd') {
      void load('mtd', next);
    } else if (customFrom && customTo) {
      void load('custom', next, customFrom, customTo);
    }
  }

  const totals = data.totals;
  const totalRoi = roiMultiple(totals.adSpend, totals.revenueCollected);

  return (
    <div>
      <TrafficSourceFilter
        selected={sources}
        onChange={changeSources}
        description="Ad spend, revenue, and volume for the selected source(s) — defaults to Meta & Google ad clicks."
        disabled={loading}
      />

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex flex-wrap gap-1 rounded-lg bg-zinc-100 p-1">
          <PeriodTypeButton label="Weekly" active={periodType === 'week'} disabled={loading} onClick={() => selectPeriodType('week')} />
          <PeriodTypeButton label="Monthly" active={periodType === 'month'} disabled={loading} onClick={() => selectPeriodType('month')} />
          <PeriodTypeButton label="Month to date" active={periodType === 'mtd'} disabled={loading} onClick={() => selectPeriodType('mtd')} />
          <PeriodTypeButton label="Custom" active={periodType === 'custom'} disabled={loading} onClick={() => selectPeriodType('custom')} />
        </div>
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

      {periodType === 'custom' && (
        <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">From</span>
            <input
              type="date"
              value={customFrom}
              max={customTo || undefined}
              disabled={loading}
              onChange={(e) => setCustomFrom(e.target.value)}
              className="rounded border border-zinc-300 px-2 py-1.5 text-xs disabled:opacity-50"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span className="font-medium text-zinc-500">To</span>
            <input
              type="date"
              value={customTo}
              min={customFrom || undefined}
              max={isoDate(new Date())}
              disabled={loading}
              onChange={(e) => setCustomTo(e.target.value)}
              className="rounded border border-zinc-300 px-2 py-1.5 text-xs disabled:opacity-50"
            />
          </label>
          <button
            type="button"
            onClick={applyCustomRange}
            disabled={!customFrom || !customTo || loading}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            Apply
          </button>
        </div>
      )}

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

      <div className={`mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-8 transition-opacity ${loading ? 'opacity-50' : ''}`}>
        <StatCard label="Ad spend" value={usd(totals.adSpend)} hint={totals.adSpendEstimated ? 'estimated' : undefined} />
        <StatCard label="Collected" value={usd(totals.revenueCollected)} />
        <StatCard label="ROI" value={roiLabel(totalRoi)} />
        <StatCard
          label="Anticipated"
          value={usd(totals.anticipatedRevenue)}
          hint="within this period"
        />
        <StatCard label="Customers created" value={totals.customersCreated.toLocaleString()} />
        <StatCard
          label="Booked ahead (new)"
          value={usd(totals.newCustomerBookedAhead)}
          hint="new customers, any future date"
        />
        <StatCard label="Follow-up bookings" value={totals.customersFollowedUp.toLocaleString()} />
        <StatCard label="Completed appts" value={totals.completedAppointments.toLocaleString()} />
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

          {view === 'table' && <PeriodTable periods={data.periods} periodType={data.periodType} />}
          {view === 'text' && <WhatsAppTextView row={totals} periodType={data.periodType} slug={slug} />}
          {view === 'chart' && periodType === 'month' && <TrendChart periods={data.periods} />}
        </div>
      )}

      <div className="mt-8 border-t border-zinc-100 pt-6">
        <button
          type="button"
          onClick={() => setShowBreakdown((v) => !v)}
          className="text-sm font-medium text-blue-600 hover:underline"
        >
          {showBreakdown ? 'Hide breakdown' : 'View breakdown'}
        </button>
        {showBreakdown && (
          <BreakdownDrilldown
            from={totals.periodStart}
            to={totals.periodEnd}
            sources={sources}
            slug={slug}
          />
        )}
      </div>

      <AdSpendEntryForm slug={slug} />
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

function WhatsAppTextView({ row, periodType, slug }: { row: MarketingAdsReportPeriod; periodType: MarketingAdsReportData['periodType']; slug?: string }) {
  const [copied, setCopied] = useState(false);
  const text = useMemo(() => formatWhatsAppReport(row, periodType, slug), [row, periodType, slug]);

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
        <span className="text-xs text-zinc-500">Ready to paste into WhatsApp — for the range shown above.</span>
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

function PeriodTable({ periods, periodType }: { periods: MarketingAdsReportPeriod[]; periodType: MarketingAdsReportData['periodType'] }) {
  return (
    <>
      {/* Mobile cards */}
      <div className="mt-4 flex flex-col gap-2 sm:hidden">
        {periods.map((row) => {
          const current = isCurrentPeriod(row);
          const roi = roiMultiple(row.adSpend, row.revenueCollected);
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
              <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1.5 text-sm">
                <div className="text-zinc-500">Ad spend</div>
                <div className="text-right tabular-nums"><AdSpendCell row={row} /></div>
                <div className="text-zinc-500">Collected</div>
                <div className="text-right tabular-nums">{usdExact(row.revenueCollected)}</div>
                <div className="text-zinc-500">ROI</div>
                <div className="text-right tabular-nums font-medium">{roiLabel(roi)}</div>
                <div className="text-zinc-500" title="Upcoming appointments starting within this period only — the breakdown below can show a larger total, since it includes upcoming appointments in later periods too.">Anticipated</div>
                <div className="text-right tabular-nums">{usdExact(row.anticipatedRevenue)}</div>
                <div className="text-zinc-500">Customers created</div>
                <div className="text-right tabular-nums">{row.customersCreated}</div>
                <div
                  className="text-zinc-500"
                  title="What this period's new customers (Customers created, above) have already booked ahead, any future date — not limited to this period."
                >
                  Booked ahead (new)
                </div>
                <div className="text-right tabular-nums">{usdExact(row.newCustomerBookedAhead)}</div>
                <div className="text-zinc-500">Follow-up bookings</div>
                <div className="text-right tabular-nums">{row.customersFollowedUp}</div>
                <div className="text-zinc-500">Completed appts</div>
                <div className="text-right tabular-nums">{row.completedAppointments}</div>
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
              <th className="px-3 py-2">Period</th>
              <th className="px-3 py-2 text-right">Ad spend</th>
              <th className="px-3 py-2 text-right">Collected</th>
              <th className="px-3 py-2 text-right">ROI</th>
              <th
                className="px-3 py-2 text-right"
                title="Upcoming appointments starting within this period only — the breakdown below can show a larger total, since it includes upcoming appointments in later periods too."
              >
                Anticipated
              </th>
              <th className="px-3 py-2 text-right">Customers created</th>
              <th
                className="px-3 py-2 text-right"
                title="What this period's new customers (Customers created) have already booked ahead, any future date — not limited to this period."
              >
                Booked ahead (new)
              </th>
              <th className="px-3 py-2 text-right">Follow-up</th>
              <th className="px-3 py-2 text-right">Completed appts</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {periods.map((row) => {
              const current = isCurrentPeriod(row);
              const roi = roiMultiple(row.adSpend, row.revenueCollected);
              return (
                <tr key={row.periodStart} className={current ? 'bg-blue-50' : 'hover:bg-zinc-50'}>
                  <td className="px-3 py-2 font-medium">
                    <div className="flex items-center gap-2">
                      {fmtPeriodLabel(row, periodType)}
                      {row.monthInProgress && periodType === 'MONTH' && <InProgressBadge />}
                      {current && !row.monthInProgress && <CurrentBadge />}
                    </div>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600"><AdSpendCell row={row} /></td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usdExact(row.revenueCollected)}</td>
                  <td className="px-3 py-2 text-right tabular-nums font-medium">{roiLabel(roi)}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usdExact(row.anticipatedRevenue)}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{row.customersCreated}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usdExact(row.newCustomerBookedAhead)}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{row.customersFollowedUp}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{row.completedAppointments}</td>
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

/** Per-customer "expand to see appointments/submissions" state shared by CompletedList and
 * UpcomingList below — the same Square customer can appear in both, and expanding it in one
 * should show it already-expanded (and already-fetched, not re-fetched) in the other. Fetched
 * lazily, one customer at a time, only on the owner's own click (design.md's "fetch on click"
 * decision) — the breakdown's own load stays fast regardless of how many customers are in range.
 */
interface HistoryExpandState {
  isAppointmentsOpen(customerId: string): boolean;
  isSubmissionsOpen(customerId: string): boolean;
  isLoading(customerId: string): boolean;
  history(customerId: string): MarketingCustomerHistory | undefined;
  toggleAppointments(customerId: string): void;
  toggleSubmissions(customerId: string): void;
}

function useCustomerHistoryExpand(): HistoryExpandState {
  const [expandedAppointments, setExpandedAppointments] = useState<Set<string>>(new Set());
  const [expandedSubmissions, setExpandedSubmissions] = useState<Set<string>>(new Set());
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

  function toggle(set: Set<string>, setSet: (updater: (s: Set<string>) => Set<string>) => void, customerId: string) {
    const opening = !set.has(customerId);
    setSet((prev) => {
      const next = new Set(prev);
      if (next.has(customerId)) next.delete(customerId);
      else next.add(customerId);
      return next;
    });
    if (opening) ensureLoaded(customerId);
  }

  return {
    isAppointmentsOpen: (id) => expandedAppointments.has(id),
    isSubmissionsOpen: (id) => expandedSubmissions.has(id),
    isLoading: (id) => loadingIds.has(id),
    history: (id) => historyByCustomer.get(id),
    toggleAppointments: (id) => toggle(expandedAppointments, setExpandedAppointments, id),
    toggleSubmissions: (id) => toggle(expandedSubmissions, setExpandedSubmissions, id),
  };
}

/** The expand affordance itself — shown under a completed/upcoming row. Three states: not yet
 * clicked (plain links, count unknown), loading (spinner), loaded (real HistoryToggle counts,
 * matching ContactsTable's exact convention including its "no history" plain-text fallback). */
function CustomerHistoryExpand({ customerId, expand }: { customerId: string; expand: HistoryExpandState }) {
  const appointmentsOpen = expand.isAppointmentsOpen(customerId);
  const submissionsOpen = expand.isSubmissionsOpen(customerId);
  const hist = expand.history(customerId);
  const loading = expand.isLoading(customerId);

  return (
    <div className="mt-2 flex flex-col gap-2 border-t border-zinc-100 pt-2">
      <div className="flex flex-wrap items-center gap-3">
        {hist ? (
          <>
            <HistoryToggle label="Appointments" count={hist.appointments.length} open={appointmentsOpen} onClick={() => expand.toggleAppointments(customerId)} />
            <HistoryToggle label="Submissions" count={hist.submissions.length} open={submissionsOpen} onClick={() => expand.toggleSubmissions(customerId)} />
          </>
        ) : loading ? (
          <span className="inline-flex items-center gap-1.5 text-xs text-zinc-400">
            <Spinner className="h-3 w-3" /> Loading history…
          </span>
        ) : (
          <>
            <button type="button" onClick={() => expand.toggleAppointments(customerId)} className="text-xs font-medium text-blue-600 hover:underline">
              Appointments
            </button>
            <button type="button" onClick={() => expand.toggleSubmissions(customerId)} className="text-xs font-medium text-blue-600 hover:underline">
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

function BreakdownDrilldown({
  from, to, sources, slug,
}: { from: string; to: string; sources: Set<TrafficSourceKey>; slug?: string }) {
  const [data, setData] = useState<MarketingAnalyticsData | null>(null);
  const [segment, setSegment] = useState<SegmentKey>('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const historyExpand = useCustomerHistoryExpand();

  useEffect(() => {
    let cancelled = false;
    api.getMarketingAnalytics(from, to, sources, slug)
      .then((result) => { if (!cancelled) { setData(result); setError(''); } })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load breakdown.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [from, to, sources, slug]);

  if (loading) {
    return (
      <div className="mt-4 flex items-center gap-2 text-sm text-zinc-500">
        <Spinner className="h-4 w-4" /> Loading breakdown…
      </div>
    );
  }
  if (error) return <p className="mt-4 text-sm text-red-600">{error}</p>;
  if (!data) return null;

  const activeSegment: MarketingAnalyticsSegment = data[segment];
  const upcomingForSegment = data.upcoming.filter((a) => segment === 'all' || (segment === 'fresh' ? a.freshFromAds : !a.freshFromAds));
  const upcomingTotal = upcomingForSegment.reduce((sum, a) => sum + a.price, 0);
  const completedForSegment = data.completed.filter((a) => segment === 'all' || (segment === 'fresh' ? a.freshFromAds : !a.freshFromAds));
  const completedTotal = completedForSegment.reduce((sum, a) => sum + a.collected, 0);
  const byChannel = completedForSegment.reduce<Record<string, number>>((acc, a) => {
    acc[a.paymentChannel] = (acc[a.paymentChannel] ?? 0) + a.collected;
    return acc;
  }, {});

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
          <h3 className="text-sm font-medium text-zinc-500">Completed appointments &amp; collected revenue</h3>
          <span className="text-xs text-zinc-400">already rung up — cash, card, or a provider&apos;s cash note; includes follow-ups</span>
        </div>
        {completedForSegment.length === 0 ? (
          <div className="mt-3 rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500">
            No completed, paid appointments for {segmentLabel(segment).toLowerCase()} in this range.
          </div>
        ) : (
          <>
            <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <StatCard label="Completed appointments" value={completedForSegment.length.toLocaleString()} />
              <StatCard label="Total collected" value={usd(completedTotal)} />
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {Object.entries(byChannel).map(([channel, amount]) => (
                <ChannelBreakdownBadge key={channel} channel={channel} amount={amount} />
              ))}
            </div>
            <CompletedList appointments={completedForSegment} historyExpand={historyExpand} />
          </>
        )}
      </div>

      <div className="mt-6">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-sm font-medium text-zinc-500">Anticipated from upcoming appointments</h3>
          <span className="text-xs text-zinc-400">not counted above — nothing&apos;s been rung up yet; includes follow-ups</span>
        </div>
        <p className="mt-1 text-xs text-zinc-400">
          Every future appointment for these customers, any date — not limited to the range selected
          above. That&apos;s why this total can be larger than the &quot;Anticipated&quot; figure shown
          per period further up: that one only counts appointments starting within that specific period.
        </p>
        {upcomingForSegment.length === 0 ? (
          <div className="mt-3 rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500">
            No upcoming appointments for {segmentLabel(segment).toLowerCase()} right now.
          </div>
        ) : (
          <>
            <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <StatCard label="Upcoming appointments" value={upcomingForSegment.length.toLocaleString()} />
              <StatCard label="Anticipated revenue (all future dates)" value={usd(upcomingTotal)} />
            </div>
            <UpcomingList appointments={upcomingForSegment} historyExpand={historyExpand} />
          </>
        )}
      </div>
    </div>
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

function CompletedList({ appointments, historyExpand }: { appointments: MarketingCompletedAppointment[]; historyExpand: HistoryExpandState }) {
  return (
    <>
      <div className="mt-3 flex flex-col gap-2 sm:hidden">
        {appointments.map((a) => (
          <div key={a.customerId + a.date + a.serviceName} className="rounded-lg p-3 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{a.customerName}</span>
              <FreshBadge fresh={a.freshFromAds} />
            </div>
            <div className="mt-1 flex items-center justify-between gap-2 text-sm text-zinc-600">
              <span>{a.serviceName}</span>
              <span className="font-medium tabular-nums">{usdExact(a.collected)}</span>
            </div>
            <div className="mt-1 flex items-center justify-between gap-2">
              <span className="text-xs text-zinc-400">{fmtDay(a.date)}</span>
              <PaymentChannelBadge channel={a.paymentChannel} />
            </div>
            <CustomerHistoryExpand customerId={a.customerId} expand={historyExpand} />
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
              <th className="px-3 py-2 text-right">Collected</th>
              <th className="px-3 py-2">Payment</th>
              <th className="px-3 py-2">Source</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {appointments.map((a) => (
              <Fragment key={a.customerId + a.date + a.serviceName}>
                <tr className="hover:bg-zinc-50">
                  <td className="px-3 py-2 font-medium">{a.customerName}</td>
                  <td className="px-3 py-2 text-zinc-600">{a.serviceName}</td>
                  <td className="px-3 py-2 text-zinc-600">{fmtDay(a.date)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{usdExact(a.collected)}</td>
                  <td className="px-3 py-2"><PaymentChannelBadge channel={a.paymentChannel} /></td>
                  <td className="px-3 py-2"><FreshBadge fresh={a.freshFromAds} /></td>
                </tr>
                <tr className="bg-zinc-50/50">
                  <td colSpan={6} className="px-3 pb-2">
                    <CustomerHistoryExpand customerId={a.customerId} expand={historyExpand} />
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

function UpcomingList({ appointments, historyExpand }: { appointments: MarketingUpcomingAppointment[]; historyExpand: HistoryExpandState }) {
  return (
    <>
      <div className="mt-3 flex flex-col gap-2 sm:hidden">
        {appointments.map((a) => (
          <div key={a.customerId + a.startAt} className="rounded-lg p-3 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{a.customerName}</span>
              <FreshBadge fresh={a.freshFromAds} />
            </div>
            <div className="mt-1 flex items-center justify-between gap-2 text-sm text-zinc-600">
              <span>{a.serviceName}</span>
              <span className="font-medium tabular-nums">{usdExact(a.price)}</span>
            </div>
            <div className="mt-1 text-xs text-zinc-400">{fmtAppointment(a.startAt)}</div>
            <CustomerHistoryExpand customerId={a.customerId} expand={historyExpand} />
          </div>
        ))}
      </div>

      <div className="mt-3 hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Customer</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">When</th>
              <th className="px-3 py-2 text-right">Price</th>
              <th className="px-3 py-2">Source</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {appointments.map((a) => (
              <Fragment key={a.customerId + a.startAt}>
                <tr className="hover:bg-zinc-50">
                  <td className="px-3 py-2 font-medium">{a.customerName}</td>
                  <td className="px-3 py-2 text-zinc-600">{a.serviceName}</td>
                  <td className="px-3 py-2 text-zinc-600">{fmtAppointment(a.startAt)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{usdExact(a.price)}</td>
                  <td className="px-3 py-2"><FreshBadge fresh={a.freshFromAds} /></td>
                </tr>
                <tr className="bg-zinc-50/50">
                  <td colSpan={5} className="px-3 pb-2">
                    <CustomerHistoryExpand customerId={a.customerId} expand={historyExpand} />
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
