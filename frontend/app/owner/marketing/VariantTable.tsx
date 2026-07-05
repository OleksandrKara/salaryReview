import type { MarketingVariantStat } from '../../lib/types';
import VariantLinkButton from './VariantLinkButton';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

function ActiveDot({ active }: { active: boolean }) {
  return active ? (
    <span className="text-emerald-600">●</span>
  ) : (
    <span className="text-zinc-300">●</span>
  );
}

export default function VariantTable({ variants }: { variants: MarketingVariantStat[] }) {
  if (variants.length === 0) return null;

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {variants.map((v) => (
          <div key={v.variantId} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{v.name}</span>
              <span className="flex items-center gap-1.5 text-xs text-zinc-500">
                <ActiveDot active={v.active} /> weight {v.weight}
              </span>
            </div>
            <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
              <div>
                <dt className="text-xs text-zinc-500">Page Views</dt>
                <dd className="tabular-nums">{v.pageViews.toLocaleString('en-US')}</dd>
              </div>
              <div>
                <dt className="text-xs text-zinc-500">Bookings</dt>
                <dd className="tabular-nums">{v.bookingsCompleted.toLocaleString('en-US')}</dd>
              </div>
              <div>
                <dt className="text-xs text-zinc-500">Conversion</dt>
                <dd className="tabular-nums text-zinc-500">{pct(v.conversionRate)}</dd>
              </div>
            </dl>
            {v.deepLinkUrl && (
              <div className="mt-3 border-t border-zinc-100 pt-3">
                <VariantLinkButton url={v.deepLinkUrl} />
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Variant</th>
              <th className="px-3 py-2 text-right">Weight</th>
              <th className="px-3 py-2 text-center">Active</th>
              <th className="px-3 py-2 text-right">Page Views</th>
              <th className="px-3 py-2 text-right">Bookings</th>
              <th className="px-3 py-2 text-right">Conversion %</th>
              <th className="px-3 py-2">Link</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {variants.map((v) => (
              <tr key={v.variantId} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">{v.name}</td>
                <td className="px-3 py-2 text-right tabular-nums">{v.weight}</td>
                <td className="px-3 py-2 text-center">
                  <ActiveDot active={v.active} />
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{v.pageViews.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{v.bookingsCompleted.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{pct(v.conversionRate)}</td>
                <td className="px-3 py-2">
                  {v.deepLinkUrl ? <VariantLinkButton url={v.deepLinkUrl} /> : <span className="text-zinc-300">—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
