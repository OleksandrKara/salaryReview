import type { MarketingConversionsData } from '../../lib/types';

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

export default function ConversionsPanel({ data }: { data: MarketingConversionsData }) {
  const maxPoints = Math.max(1, ...data.rows.map((r) => r.points));
  return (
    <section className="mt-6" aria-label="Conversions">
      <h2 className="mb-2 text-sm font-medium text-zinc-500">Conversions</h2>
      <div className="rounded-lg border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900">
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat label="Visitors" value={data.pageViews.toLocaleString()} />
          <Stat label="Conversion points" value={data.totalPoints.toLocaleString()} />
          <Stat label="Paying customers" value={data.payingCustomers.toLocaleString()} />
          <Stat label="Revenue from them" value={usd.format(data.revenue)} />
        </div>
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[32rem] text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-zinc-500">
              <tr>
                <th className="py-1 pr-3 font-medium">Conversion</th>
                <th className="py-1 pr-3 text-right font-medium">Weight</th>
                <th className="py-1 pr-3 text-right font-medium">Times</th>
                <th className="py-1 pr-3 text-right font-medium">Points</th>
                <th className="w-1/3 py-1 font-medium" aria-hidden="true" />
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r) => (
                <tr key={r.key} className="border-t border-zinc-100 dark:border-zinc-800">
                  <td className="py-2 pr-3">
                    <div className="font-medium">{r.label}</div>
                    <div className="text-xs text-zinc-500">{r.hint}</div>
                  </td>
                  <td className="py-2 pr-3 text-right tabular-nums">{r.weight}</td>
                  <td className="py-2 pr-3 text-right tabular-nums">{r.count.toLocaleString()}</td>
                  <td className="py-2 pr-3 text-right font-medium tabular-nums">{r.points.toLocaleString()}</td>
                  <td className="py-2">
                    <div className="h-2 rounded bg-zinc-100 dark:bg-zinc-800">
                      <div className="h-2 rounded bg-emerald-500" style={{ width: `${(r.points / maxPoints) * 100}%` }} />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-xs text-zinc-500">
          Taps count each visitor once. Consultations are people who booked here. Deposit and full payment come
          from Square payments made after the person left their details. Revenue is everything those people paid.
        </p>
      </div>
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-zinc-500">{label}</div>
      <div className="text-xl font-semibold tabular-nums">{value}</div>
    </div>
  );
}
