'use client';

import { useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { api } from '../../../lib/api';
import type { GooglePpcData } from '../../../lib/types';
import PeriodFilter from '../PeriodFilter';
import { parsePeriodParams, periodToBounds } from '../period';
import type { PeriodSelection } from '../period';

const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num = (n: number) => n.toLocaleString('en-US');
const dash = '-';

const perUnit = (spend: number, n: number) => (n > 0 ? usd2.format(spend / n) : dash);

export default function GooglePpcView({ initialData, timeZone }: { initialData: GooglePpcData | null; timeZone?: string }) {
  const searchParams = useSearchParams();
  const [data, setData] = useState(initialData);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(initialData === null);
  const requestId = useRef(0);
  const [selection, setSelection] = useState<PeriodSelection>(() => parsePeriodParams(searchParams));

  async function changePeriod(next: PeriodSelection) {
    setSelection(next);
    const bounds = periodToBounds(next, timeZone);
    const id = ++requestId.current;
    setLoading(true);
    try {
      const d = await api.getGooglePpc(bounds.from, bounds.to);
      if (id !== requestId.current) return;
      setData(d);
      setFailed(false);
    } catch {
      if (id === requestId.current) setFailed(true);
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }

  return (
    <div className="mt-4 space-y-6">
      <PeriodFilter value={selection} onChange={changePeriod} timeZone={timeZone} disabled={loading} />
      {failed || !data ? (
        <p className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Google Ads data could not be loaded. Try again in a moment.
        </p>
      ) : !data.hasSpend ? (
        <p className="rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No Google Ads spend was synced for this period.
        </p>
      ) : (
        <div className={`space-y-6 transition-opacity ${loading ? 'opacity-50' : ''}`} aria-busy={loading}>
          <Kpis data={data} />
          <Campaigns data={data} />
          <Pages data={data} />
        </div>
      )}
    </div>
  );
}

function Kpi({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="min-w-0 rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums text-zinc-900 sm:text-2xl">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-zinc-500">{sub}</div>}
    </div>
  );
}

function Kpis({ data }: { data: GooglePpcData }) {
  const r = data.results;
  const roi = data.spend > 0 ? ((r.revenue - data.spend) / data.spend) * 100 : null;
  const roas = data.spend > 0 ? r.revenue / data.spend : null;
  return (
    <section aria-label="Google Ads results">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi label="Spend" value={usd0.format(data.spend)} sub={`${num(data.clicks)} clicks, ${num(data.impressions)} impressions`} />
        <Kpi label="Cost per click" value={perUnit(data.spend, data.clicks)} sub={`${num(r.visitors)} landing page visitors`} />
        <Kpi label="Cost per consultation" value={perUnit(data.spend, r.consultations)} sub={`${num(r.consultations)} consultations`} />
        <Kpi label="Cost per deposit" value={perUnit(data.spend, r.deposits)} sub={`${num(r.deposits)} deposits, ${num(r.fullPayments)} full payments`} />
        <Kpi label="Revenue" value={usd0.format(r.revenue)} sub={`${num(r.payingCustomers)} paying customers`} />
        <Kpi label="ROAS" value={roas === null ? dash : `${roas.toFixed(2)}x`} sub="Revenue divided by spend" />
        <Kpi label="ROI" value={roi === null ? dash : `${roi.toFixed(0)}%`} sub="(Revenue - spend) / spend" />
        <Kpi label="ROMI" value={roi === null ? dash : `${roi.toFixed(0)}%`} sub="Same as ROI until margins are set" />
      </div>
      <p className="mt-2 text-xs text-zinc-500">
        Revenue is Square payments from customers whose first visit came from Google Ads, counted after their first contact.
        Bookings are matched via contact-capture data and may slightly undercount.
      </p>
    </section>
  );
}

function Campaigns({ data }: { data: GooglePpcData }) {
  const rows = data.campaigns;
  return (
    <section aria-label="Campaigns">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">Campaigns</h2>
      <ul className="space-y-2 sm:hidden">
        {rows.map((c) => (
          <li key={c.campaignId} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
            <div className="flex items-start justify-between gap-2">
              <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{c.name}</span>
              <span className="text-sm font-semibold tabular-nums">{usd0.format(c.spend)}</span>
            </div>
            <dl className="mt-2 grid grid-cols-3 gap-2 text-xs">
              <Cell label="Clicks" value={num(c.clicks)} />
              <Cell label="CPC" value={perUnit(c.spend, c.clicks)} />
              <Cell label="Conversions" value={num(Math.round(c.platformConversions))} />
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Campaign</th>
              <th className="px-3 py-2 text-right font-medium">Spend</th>
              <th className="px-3 py-2 text-right font-medium">Impressions</th>
              <th className="px-3 py-2 text-right font-medium">Clicks</th>
              <th className="px-3 py-2 text-right font-medium">CPC</th>
              <th className="px-3 py-2 text-right font-medium" title="Conversions reported by Google Ads itself">Google conversions</th>
              <th className="px-3 py-2 text-right font-medium">Cost per conversion</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.campaignId} className="border-t border-zinc-100">
                <td className="px-3 py-2 font-medium text-zinc-900">{c.name}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd0.format(c.spend)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(c.impressions)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(c.clicks)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, c.clicks)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(Math.round(c.platformConversions))}</td>
                <td className="px-3 py-2 text-right tabular-nums">{perUnit(c.spend, c.platformConversions)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Pages({ data }: { data: GooglePpcData }) {
  if (data.pages.length === 0) return null;
  return (
    <section aria-label="Results by landing page">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">Results by landing page</h2>
      <div className="overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200">
        <table className="w-full min-w-[34rem] text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Page</th>
              <th className="px-3 py-2 text-right font-medium">Visitors</th>
              <th className="px-3 py-2 text-right font-medium">Taps</th>
              <th className="px-3 py-2 text-right font-medium">Consultations</th>
              <th className="px-3 py-2 text-right font-medium">Deposits</th>
              <th className="px-3 py-2 text-right font-medium">Full payments</th>
              <th className="px-3 py-2 text-right font-medium">Revenue</th>
            </tr>
          </thead>
          <tbody>
            {data.pages.map((p) => (
              <tr key={p.slug} className="border-t border-zinc-100">
                <td className="px-3 py-2 font-medium text-zinc-900">{p.name}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.visitors)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.smsTaps + p.callTaps)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.consultations)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.deposits)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(p.fullPayments)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(p.revenue)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-zinc-500">{label}</dt>
      <dd className="font-medium tabular-nums text-zinc-900">{value}</dd>
    </div>
  );
}
