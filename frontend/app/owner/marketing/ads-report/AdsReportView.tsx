'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type { MarketingAdsReportData, MarketingAdsReportPeriod, TrafficSourceKey } from '../../../lib/types';
import TrafficSourceFilter, { ADS_ONLY_SOURCES } from '../TrafficSourceFilter';

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

function fmtPeriodLabel(row: MarketingAdsReportPeriod, periodType: 'WEEK' | 'MONTH'): string {
  const start = parseLocalDate(row.periodStart);
  if (periodType === 'MONTH') {
    return start.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
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

type PeriodType = 'week' | 'month';

const WEEK_PRESETS = [4, 8, 12];
const MONTH_PRESETS = [3, 6, 12];

export default function AdsReportView({ initialData, slug }: { initialData: MarketingAdsReportData; slug?: string }) {
  const [data, setData] = useState(initialData);
  const [periodType, setPeriodType] = useState<PeriodType>('week');
  const [rangeCount, setRangeCount] = useState(8);
  const [isCustom, setIsCustom] = useState(false);
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [sources, setSources] = useState<Set<TrafficSourceKey>>(() => new Set(ADS_ONLY_SOURCES));
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  function computeRange(pt: PeriodType, n: number) {
    return pt === 'week' ? lastNWeeksRange(n) : lastNMonthsRange(n);
  }

  async function load(nextPeriodType: PeriodType, nextSources: Set<TrafficSourceKey>, from: string, to: string) {
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
    setIsCustom(false);
    const defaultN = pt === 'week' ? 8 : 6;
    setRangeCount(defaultN);
    const range = computeRange(pt, defaultN);
    void load(pt, sources, range.from, range.to);
  }

  function selectRangePreset(n: number) {
    setRangeCount(n);
    setIsCustom(false);
    const range = computeRange(periodType, n);
    void load(periodType, sources, range.from, range.to);
  }

  function applyCustomRange() {
    if (!customFrom || !customTo) return;
    setIsCustom(true);
    void load(periodType, sources, customFrom, customTo);
  }

  function changeSources(next: Set<TrafficSourceKey>) {
    setSources(next);
    const range = isCustom ? { from: customFrom, to: customTo } : computeRange(periodType, rangeCount);
    void load(periodType, next, range.from, range.to);
  }

  const presets = periodType === 'week' ? WEEK_PRESETS : MONTH_PRESETS;
  const totals = data.totals;
  const totalRoi = roiMultiple(totals.adSpend, totals.revenueCollected);

  return (
    <div>
      <TrafficSourceFilter
        selected={sources}
        onChange={changeSources}
        description="Ad spend, revenue, and volume for the selected source(s) — defaults to Meta & Google ad clicks."
      />

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex gap-1 rounded-lg bg-zinc-100 p-1">
          <PeriodTypeButton label="Weekly" active={periodType === 'week'} onClick={() => selectPeriodType('week')} />
          <PeriodTypeButton label="Monthly" active={periodType === 'month'} onClick={() => selectPeriodType('month')} />
        </div>
        <div className="flex flex-wrap gap-2">
          {presets.map((n) => (
            <PresetButton
              key={n}
              label={periodType === 'week' ? `Last ${n} weeks` : `Last ${n} months`}
              active={!isCustom && rangeCount === n}
              onClick={() => selectRangePreset(n)}
            />
          ))}
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">From</span>
          <input
            type="date"
            value={customFrom}
            max={customTo || undefined}
            onChange={(e) => setCustomFrom(e.target.value)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">To</span>
          <input
            type="date"
            value={customTo}
            min={customFrom || undefined}
            max={isoDate(new Date())}
            onChange={(e) => setCustomTo(e.target.value)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
          />
        </label>
        <button
          type="button"
          onClick={applyCustomRange}
          className="rounded bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
        >
          Apply
        </button>
      </div>

      {error ? <p className="mt-3 text-sm text-red-600">{error}</p> : null}

      <p className="mt-4 text-xs text-zinc-500">
        {data.periods.length > 0 ? fmtDateRange(totals.periodStart, totals.periodEnd) : ''}
        {loading ? ' · loading…' : ''}
      </p>

      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label="Ad spend" value={usd(totals.adSpend)} hint={totals.adSpendEstimated ? 'estimated' : undefined} />
        <StatCard label="Collected" value={usd(totals.revenueCollected)} />
        <StatCard label="ROI" value={roiLabel(totalRoi)} />
        <StatCard label="Anticipated" value={usd(totals.anticipatedRevenue)} />
        <StatCard label="Customers created" value={totals.customersCreated.toLocaleString()} />
        <StatCard label="Completed appts" value={totals.completedAppointments.toLocaleString()} />
      </div>

      {data.periods.length === 0 ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No data for this range yet.
        </div>
      ) : (
        <PeriodTable periods={data.periods} periodType={data.periodType} />
      )}
    </div>
  );
}

function PeriodTypeButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
        active ? 'bg-white text-zinc-900 shadow-sm ring-1 ring-zinc-200' : 'text-zinc-500 hover:text-zinc-700'
      }`}
    >
      {label}
    </button>
  );
}

function PresetButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        active
          ? 'rounded-full bg-blue-600 px-3 py-1.5 text-xs font-medium text-white'
          : 'rounded-full bg-zinc-100 px-3 py-1.5 text-xs font-medium text-zinc-700 hover:bg-zinc-200'
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
      <span title="Estimated — prorated from that month's real total, since ad spend is only entered once per month.">
        ~{usdExact(row.adSpend)}
      </span>
    );
  }
  return <span>{usdExact(row.adSpend)}</span>;
}

function PeriodTable({ periods, periodType }: { periods: MarketingAdsReportPeriod[]; periodType: 'WEEK' | 'MONTH' }) {
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
                {current && (
                  <span className="whitespace-nowrap rounded-full bg-blue-600 px-2 py-0.5 text-xs font-medium text-white">
                    Current
                  </span>
                )}
              </div>
              <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1.5 text-sm">
                <div className="text-zinc-500">Ad spend</div>
                <div className="text-right tabular-nums"><AdSpendCell row={row} /></div>
                <div className="text-zinc-500">Collected</div>
                <div className="text-right tabular-nums">{usdExact(row.revenueCollected)}</div>
                <div className="text-zinc-500">ROI</div>
                <div className="text-right tabular-nums font-medium">{roiLabel(roi)}</div>
                <div className="text-zinc-500">Anticipated</div>
                <div className="text-right tabular-nums">{usdExact(row.anticipatedRevenue)}</div>
                <div className="text-zinc-500">Customers created</div>
                <div className="text-right tabular-nums">{row.customersCreated}</div>
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
              <th className="px-3 py-2 text-right">Anticipated</th>
              <th className="px-3 py-2 text-right">Customers created</th>
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
                      {current && (
                        <span className="whitespace-nowrap rounded-full bg-blue-600 px-2 py-0.5 text-[10px] font-medium text-white">
                          Current
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600"><AdSpendCell row={row} /></td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usdExact(row.revenueCollected)}</td>
                  <td className="px-3 py-2 text-right tabular-nums font-medium">{roiLabel(roi)}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usdExact(row.anticipatedRevenue)}</td>
                  <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{row.customersCreated}</td>
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
