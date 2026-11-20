import type { MarketingConversionsData, MarketingSourceRow, TrafficSourceKey } from '../../lib/types';
import { SOURCE_LABELS } from './TrafficSourceFilter';

const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const num = (n: number) => n.toLocaleString('en-US');
const dash = '-';

const PAID = new Set(['meta_ads', 'google_ads']);
const LABELS: Record<string, string> = { ...SOURCE_LABELS, other: 'Other' };
// Paid channels first, Google Ads leading (the current focus), then everything else.
const ORDER = ['google_ads', 'meta_ads', 'google_organic', 'instagram_organic', 'yelp', 'direct', 'other'];

function pct(a: number, b: number): string {
  return b > 0 ? `${((a / b) * 100).toFixed(1)}%` : dash;
}
function perUnit(spend: number, n: number): string {
  return n > 0 ? usd2.format(spend / n) : dash;
}

export default function OverviewSummary({
  data,
  selected,
  loading,
}: {
  data: MarketingConversionsData;
  selected: Set<TrafficSourceKey>;
  loading: boolean;
}) {
  return (
    <div className={`space-y-6 transition-opacity ${loading ? 'opacity-50' : ''}`} aria-busy={loading}>
      <Kpis data={data} />
      <GoogleAdsPanel data={data} />
      <SourceBreakdown data={data} selected={selected} />
      <WhatCounted data={data} />
    </div>
  );
}

function SectionTitle({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="mb-2">
      <h2 className="text-sm font-semibold text-zinc-900">{title}</h2>
      {hint && <p className="mt-0.5 text-xs text-zinc-500">{hint}</p>}
    </div>
  );
}

function Kpi({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="min-w-0 rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
      <div className="text-xs font-medium text-zinc-500">{label}</div>
      <div className="mt-1 truncate text-xl font-semibold tabular-nums text-zinc-900 sm:text-2xl">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-zinc-500">{sub}</div>}
    </div>
  );
}

function Kpis({ data }: { data: MarketingConversionsData }) {
  const consultations = data.rows.find((r) => r.key === 'consultation')?.count ?? 0;
  return (
    <section aria-label="Totals">
      <div className="grid grid-cols-2 gap-2 sm:gap-3 lg:grid-cols-4">
        <Kpi label="Visitors" value={num(data.pageViews)} sub={`${pct(consultations, data.pageViews)} booked a consultation`} />
        <Kpi label="Conversion points" value={num(data.totalPoints)} sub="Weighted by value" />
        <Kpi label="Paying customers" value={num(data.payingCustomers)} sub="Paid in Square after leaving details" />
        <Kpi label="Revenue" value={usd0.format(data.revenue)} sub={data.pageViews > 0 ? `${usd2.format(data.revenue / data.pageViews)} per visitor` : undefined} />
      </div>
    </section>
  );
}

function GoogleAdsPanel({ data }: { data: MarketingConversionsData }) {
  const ads = data.googleAds;
  const row = data.bySource.find((r) => r.key === 'google_ads');
  if (!ads && (!row || row.visitors === 0)) return null;
  const spend = ads?.spend ?? 0;
  const clicks = ads?.clicks ?? 0;
  const visitors = row?.visitors ?? 0;
  const consultations = row?.consultations ?? 0;
  const deposits = row?.deposits ?? 0;
  const revenue = row?.revenue ?? 0;
  const roas = spend > 0 ? revenue / spend : null;
  return (
    <section aria-label="Google Ads">
      <SectionTitle
        title="Google Ads (PPC)"
        hint="Always about Google Ads only, for the selected period. Spend comes from the Google Ads sync; results come from visitors who arrived with a Google click."
      />
      <div className="rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
        <div className="grid grid-cols-2 gap-x-3 gap-y-4 sm:grid-cols-3 lg:grid-cols-4">
          <Metric label="Spend" value={ads ? usd2.format(spend) : dash} strong />
          <Metric label="Clicks" value={ads ? num(clicks) : dash} sub={ads ? `${num(ads.impressions)} impressions` : undefined} />
          <Metric label="Avg. cost per click" value={ads ? perUnit(spend, clicks) : dash} />
          <Metric label="Click to visit" value={ads ? pct(visitors, clicks) : dash} sub={`${num(visitors)} visitors on site`} hintTitle="Visitors on the site divided by Google clicks. Low means people leave before the page loads." />
          <Metric label="Consultations" value={num(consultations)} sub={ads ? `${perUnit(spend, consultations)} each` : undefined} strong />
          <Metric label="Deposits paid" value={num(deposits)} sub={ads ? `${perUnit(spend, deposits)} each` : undefined} strong />
          <Metric label="Revenue" value={usd0.format(revenue)} sub={`${row?.payingCustomers ?? 0} paying customers`} />
          <Metric label="Return on ad spend" value={roas == null ? dash : `${roas.toFixed(2)}x`} sub={roas == null ? undefined : `${usd2.format(revenue)} per ${usd2.format(spend)}`} strong />
        </div>

        {ads && ads.campaigns.length > 0 && (
          <div className="mt-4 border-t border-zinc-100 pt-3">
            <div className="mb-2 text-xs font-medium uppercase tracking-wide text-zinc-500">Campaigns</div>
            <ul className="divide-y divide-zinc-100 sm:hidden">
              {ads.campaigns.map((c) => (
                <li key={c.campaignId} className="py-2">
                  <div className="flex items-start justify-between gap-3">
                    <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{c.name}</span>
                    <span className="shrink-0 text-sm font-semibold tabular-nums">{usd2.format(c.spend)}</span>
                  </div>
                  <div className="mt-0.5 text-xs text-zinc-500">
                    {num(c.clicks)} clicks, {perUnit(c.spend, c.clicks)} per click, {c.platformConversions.toFixed(1)} conversions in Google
                    {c.status && c.status !== 'ENABLED' ? `, ${c.status.toLowerCase()}` : ''}
                  </div>
                </li>
              ))}
            </ul>
            <div className="hidden overflow-x-auto sm:block">
              <table className="w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="py-1 pr-3 font-medium">Campaign</th>
                    <th className="py-1 pr-3 text-right font-medium">Spend</th>
                    <th className="py-1 pr-3 text-right font-medium">Clicks</th>
                    <th className="py-1 pr-3 text-right font-medium">Cost per click</th>
                    <th className="py-1 text-right font-medium" title="Conversions as counted by Google Ads itself">Google conversions</th>
                  </tr>
                </thead>
                <tbody>
                  {ads.campaigns.map((c) => (
                    <tr key={c.campaignId} className="border-t border-zinc-100">
                      <td className="py-2 pr-3">
                        <span className="font-medium text-zinc-900">{c.name}</span>
                        {c.status && c.status !== 'ENABLED' && <span className="ml-2 rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-600">{c.status.toLowerCase()}</span>}
                      </td>
                      <td className="py-2 pr-3 text-right tabular-nums">{usd2.format(c.spend)}</td>
                      <td className="py-2 pr-3 text-right tabular-nums">{num(c.clicks)}</td>
                      <td className="py-2 pr-3 text-right tabular-nums">{perUnit(c.spend, c.clicks)}</td>
                      <td className="py-2 text-right tabular-nums">{c.platformConversions.toFixed(1)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
        {!ads && <p className="mt-3 text-xs text-zinc-500">No Google Ads spend synced for this period, so cost figures are not shown.</p>}
      </div>
    </section>
  );
}

function Metric({ label, value, sub, strong, hintTitle }: { label: string; value: string; sub?: string; strong?: boolean; hintTitle?: string }) {
  return (
    <div className="min-w-0" title={hintTitle}>
      <div className="text-xs text-zinc-500">{label}</div>
      <div className={`truncate tabular-nums ${strong ? 'text-xl font-semibold text-zinc-900' : 'text-lg font-medium text-zinc-800'}`}>{value}</div>
      {sub && <div className="text-xs text-zinc-500">{sub}</div>}
    </div>
  );
}

function SourceBreakdown({ data, selected }: { data: MarketingConversionsData; selected: Set<TrafficSourceKey> }) {
  const rows = ORDER.map((k) => data.bySource.find((r) => r.key === k)).filter((r): r is MarketingSourceRow => !!r)
    .filter((r) => r.key !== 'other' || r.visitors > 0 || r.consultations > 0 || r.revenue > 0);
  const taps = (r: MarketingSourceRow) => r.smsTaps + r.callTaps;
  return (
    <section aria-label="Results by traffic source">
      <SectionTitle
        title="Results by traffic source"
        hint="Every source side by side, paid first. Each person is counted under the source of their first visit. Highlighted rows are the sources selected above."
      />
      <ul className="space-y-2 sm:hidden">
        {rows.map((r) => (
          <li key={r.key} className={`rounded-xl p-3 ring-1 ${selected.has(r.key as TrafficSourceKey) ? 'bg-emerald-50/60 ring-emerald-200' : 'bg-white ring-zinc-200'}`}>
            <div className="flex items-center justify-between gap-2">
              <SourceName k={r.key} />
              <span className="text-sm font-semibold tabular-nums">{usd0.format(r.revenue)}</span>
            </div>
            <dl className="mt-2 grid grid-cols-3 gap-x-2 gap-y-2 text-xs">
              <Cell label="Visitors" value={num(r.visitors)} />
              <Cell label="Taps" value={num(taps(r))} />
              <Cell label="Consults" value={num(r.consultations)} />
              <Cell label="Deposits" value={num(r.deposits)} />
              <Cell label="Full pay" value={num(r.fullPayments)} />
              <Cell label="Consult rate" value={pct(r.consultations, r.visitors)} />
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Source</th>
              <th className="px-3 py-2 text-right font-medium">Visitors</th>
              <th className="px-3 py-2 text-right font-medium" title="Visitors who tapped a text or call link">Taps</th>
              <th className="px-3 py-2 text-right font-medium">Consultations</th>
              <th className="px-3 py-2 text-right font-medium">Consult rate</th>
              <th className="px-3 py-2 text-right font-medium">Deposits</th>
              <th className="px-3 py-2 text-right font-medium">Full pay</th>
              <th className="px-3 py-2 text-right font-medium">Points</th>
              <th className="px-3 py-2 text-right font-medium">Revenue</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className={`border-t border-zinc-100 ${selected.has(r.key as TrafficSourceKey) ? 'bg-emerald-50/60' : ''}`}>
                <td className="px-3 py-2"><SourceName k={r.key} /></td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.visitors)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(taps(r))}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.consultations)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{pct(r.consultations, r.visitors)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.deposits)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.fullPayments)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{num(r.points)}</td>
                <td className="px-3 py-2 text-right font-medium tabular-nums">{usd0.format(r.revenue)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function SourceName({ k }: { k: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-sm font-medium text-zinc-900">
      {LABELS[k] ?? k}
      {PAID.has(k) && <span className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-blue-700">Paid</span>}
    </span>
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

function WhatCounted({ data }: { data: MarketingConversionsData }) {
  const maxPoints = Math.max(1, ...data.rows.map((r) => r.points));
  return (
    <section aria-label="Conversions">
      <SectionTitle title="What counted" hint="Each kind of conversion for the selected sources, weighted by how valuable it is." />
      <div className="rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
        <ul className="space-y-3">
          {data.rows.map((r) => (
            <li key={r.key}>
              <div className="flex items-baseline justify-between gap-3">
                <div className="min-w-0">
                  <span className="text-sm font-medium text-zinc-900">{r.label}</span>
                  <span className="ml-2 text-xs text-zinc-500">worth {r.weight}</span>
                </div>
                <div className="shrink-0 text-sm tabular-nums">
                  <span className="font-semibold text-zinc-900">{num(r.count)}</span>
                  <span className="ml-2 text-xs text-zinc-500">{num(r.points)} pts</span>
                </div>
              </div>
              <div className="mt-1 h-1.5 rounded-full bg-zinc-100">
                <div className="h-1.5 rounded-full bg-emerald-500" style={{ width: `${(r.points / maxPoints) * 100}%` }} />
              </div>
              <div className="mt-0.5 text-xs text-zinc-500">{r.hint}</div>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-zinc-500">
          Taps count each visitor once. Deposit and full payment come from Square payments made after the person left their details. Revenue is everything those people paid.
        </p>
      </div>
    </section>
  );
}
