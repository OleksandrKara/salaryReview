import type { CacChannel, MarketingCac } from '../../lib/types';

const usd0 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const usd2 = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const money = (n: number | null) => (n === null ? '-' : n >= 1000 ? usd0.format(n) : usd2.format(n));

function Stat({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-zinc-500">{label}</dt>
      <dd className={`tabular-nums ${strong ? 'text-lg font-semibold text-zinc-900' : 'text-sm font-medium text-zinc-900'}`}>{value}</dd>
    </div>
  );
}

function ChannelCard({ c, total = false }: { c: CacChannel; total?: boolean }) {
  return (
    <div className={`min-w-0 rounded-xl p-4 ring-1 ${total ? 'bg-zinc-50 ring-zinc-300' : 'bg-white ring-zinc-200'}`}>
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-sm font-semibold text-zinc-900">{c.label}</h3>
        {c.spendEstimated && <span className="text-[11px] text-amber-700" title="Spend entered by hand and prorated to this period">spend estimated</span>}
      </div>
      <div className="mt-2 flex items-end justify-between gap-3">
        <div>
          <div className="text-xs text-zinc-500">CAC</div>
          <div className="text-3xl font-semibold tabular-nums text-zinc-900">{money(c.cac)}</div>
        </div>
        <div className="text-right text-xs text-zinc-500">
          {c.payers > 0 ? `${c.payers} new paying ${c.payers === 1 ? 'customer' : 'customers'}` : 'no paying customers yet'}
        </div>
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-3 border-t border-zinc-100 pt-3 sm:grid-cols-4">
        <Stat label="Ad spend" value={usd0.format(c.spend)} />
        <Stat label="Leads" value={c.leads.toLocaleString('en-US')} />
        <Stat label="Cost per lead" value={money(c.cpl)} />
        <Stat label="Collected" value={`${usd0.format(c.collected)}${c.roas !== null ? ` (${c.roas.toFixed(1)}x)` : ''}`} />
      </dl>
    </div>
  );
}

/** Customer acquisition cost by paid channel for the selected period (Marketing Overview). */
export default function CacSummary({ data, loading }: { data: MarketingCac; loading: boolean }) {
  if (data.channels.length === 0) return null;
  return (
    <section aria-label="Customer acquisition cost" className={`transition-opacity ${loading ? 'opacity-50' : ''}`} aria-busy={loading}>
      <div className="mb-2">
        <h2 className="text-sm font-semibold text-zinc-900">Customer acquisition cost (CAC)</h2>
        <p className="mt-0.5 text-xs text-zinc-500">
          Ad spend divided by new customers who paid after arriving from that channel. Counts leads created in the period and what they paid since their first day.
        </p>
      </div>
      <div className={`grid gap-3 ${data.channels.length > 1 ? 'lg:grid-cols-3' : 'lg:grid-cols-2'}`}>
        {data.channels.map((c) => <ChannelCard key={c.key} c={c} />)}
        {data.channels.length > 1 && <ChannelCard c={data.total} total />}
      </div>
    </section>
  );
}
