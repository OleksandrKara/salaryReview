import type { MarketingConversionsData, MarketingSourceRow } from '../../lib/types';
import { SOURCE_LABELS } from './TrafficSourceFilter';

const num = (n: number) => n.toLocaleString('en-US');
const LABELS: Record<string, string> = { ...SOURCE_LABELS, other: 'Other' };
const ORDER = ['google_ads', 'meta_ads', 'google_organic', 'instagram_organic', 'yelp', 'direct', 'other'];

const COLUMNS: { label: string; get: (r: MarketingSourceRow) => number }[] = [
  { label: 'Visitors', get: (r) => r.visitors },
  { label: 'Text taps', get: (r) => r.smsTaps },
  { label: 'Call taps', get: (r) => r.callTaps },
  { label: 'Consultations', get: (r) => r.consultations },
  { label: 'Deposits', get: (r) => r.deposits },
  { label: 'Full payments', get: (r) => r.fullPayments },
];

export default function OverviewSummary({ data, loading }: { data: MarketingConversionsData; loading: boolean }) {
  const rows = ORDER.map((k) => data.bySource.find((r) => r.key === k)).filter((r): r is MarketingSourceRow => !!r)
    .filter((r) => r.key !== 'other' || COLUMNS.some((c) => c.get(r) > 0));
  const total = (c: (typeof COLUMNS)[number]) => rows.reduce((s, r) => s + c.get(r), 0);

  return (
    <section aria-label="Conversions by traffic source" className={`transition-opacity ${loading ? 'opacity-50' : ''}`} aria-busy={loading}>
      <div className="mb-2">
        <h2 className="text-sm font-semibold text-zinc-900">Conversions by traffic source</h2>
        <p className="mt-0.5 text-xs text-zinc-500">
          Each person is counted under the source of their first visit. Bookings are matched via contact-capture data and may slightly undercount.
        </p>
      </div>

      <ul className="space-y-2 sm:hidden">
        {rows.map((r) => (
          <li key={r.key} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200">
            <div className="text-sm font-medium text-zinc-900">{LABELS[r.key] ?? r.key}</div>
            <dl className="mt-2 grid grid-cols-3 gap-x-2 gap-y-2 text-xs">
              {COLUMNS.map((c) => (
                <div key={c.label}>
                  <dt className="text-zinc-500">{c.label}</dt>
                  <dd className="font-medium tabular-nums text-zinc-900">{num(c.get(r))}</dd>
                </div>
              ))}
            </dl>
          </li>
        ))}
      </ul>

      <div className="hidden overflow-x-auto rounded-xl bg-white ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-left text-sm">
          <thead className="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 font-medium">Source</th>
              {COLUMNS.map((c) => (
                <th key={c.label} className="px-3 py-2 text-right font-medium">{c.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className="border-t border-zinc-100">
                <td className="px-3 py-2 font-medium text-zinc-900">{LABELS[r.key] ?? r.key}</td>
                {COLUMNS.map((c) => (
                  <td key={c.label} className="px-3 py-2 text-right tabular-nums">{num(c.get(r))}</td>
                ))}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-zinc-200 bg-zinc-50 font-semibold">
              <td className="px-3 py-2 text-zinc-900">Total</td>
              {COLUMNS.map((c) => (
                <td key={c.label} className="px-3 py-2 text-right tabular-nums">{num(total(c))}</td>
              ))}
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
