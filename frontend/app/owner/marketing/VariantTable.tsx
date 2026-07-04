import type { MarketingVariantStat } from '../../lib/types';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

export default function VariantTable({ variants }: { variants: MarketingVariantStat[] }) {
  if (variants.length === 0) return null;

  return (
    <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
      <table className="w-full text-sm">
        <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
          <tr>
            <th className="px-3 py-2">Variant</th>
            <th className="px-3 py-2 text-right">Weight</th>
            <th className="px-3 py-2 text-center">Active</th>
            <th className="px-3 py-2 text-right">Page Views</th>
            <th className="px-3 py-2 text-right">Bookings</th>
            <th className="px-3 py-2 text-right">Conversion %</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100">
          {variants.map((v) => (
            <tr key={v.variantId} className="hover:bg-zinc-50">
              <td className="px-3 py-2 font-medium">{v.name}</td>
              <td className="px-3 py-2 text-right tabular-nums">{v.weight}</td>
              <td className="px-3 py-2 text-center">
                {v.active ? (
                  <span className="text-emerald-600">●</span>
                ) : (
                  <span className="text-zinc-300">●</span>
                )}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{v.pageViews.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums">{v.bookingsCompleted.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{pct(v.conversionRate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
