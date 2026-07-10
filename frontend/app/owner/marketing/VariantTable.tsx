import type { MarketingVariantStat } from '../../lib/types';
import VariantLinkButton from './VariantLinkButton';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

const actionButtonClass = 'rounded px-2 py-0.5 text-xs font-medium ring-1 hover:bg-zinc-50';

interface VariantActions {
  onToggleActive: (v: MarketingVariantStat) => void;
  onRename: (v: MarketingVariantStat) => void;
  onEditDescription: (v: MarketingVariantStat) => void;
  onDuplicate: (v: MarketingVariantStat) => void;
  onDelete: (v: MarketingVariantStat) => void;
  busyVariantId: string | null;
  readOnly?: boolean;
}

function Description({ v }: { v: MarketingVariantStat }) {
  if (!v.description) return <span className="text-xs italic text-zinc-400">No description</span>;
  return <p className="text-xs text-zinc-500">{v.description}</p>;
}

// A bare colored dot didn't read as clickable — a labeled pill makes it obvious this is a
// toggle, not just a status icon.
function ActiveToggle({
  v, onToggleActive, busy, readOnly,
}: { v: MarketingVariantStat; onToggleActive: (v: MarketingVariantStat) => void; busy: boolean; readOnly?: boolean }) {
  const pillClass = `rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${
    v.active ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-zinc-100 text-zinc-500 ring-zinc-200'
  }`;
  if (readOnly) return <span className={pillClass}>{v.active ? 'Active' : 'Inactive'}</span>;
  return (
    <button
      type="button"
      onClick={() => onToggleActive(v)}
      disabled={busy}
      title={v.active ? 'Active — click to turn off' : 'Inactive — click to turn on'}
      className={`${pillClass} disabled:opacity-50 ${v.active ? 'hover:bg-emerald-100' : 'hover:bg-zinc-200'}`}
    >
      {v.active ? 'Active' : 'Inactive'}
    </button>
  );
}

function ActionButtons({ v, actions }: { v: MarketingVariantStat; actions: VariantActions }) {
  const busy = actions.busyVariantId === v.variantId;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <button type="button" disabled={busy} onClick={() => actions.onRename(v)} className={`${actionButtonClass} text-blue-600 ring-blue-200`}>
        Rename
      </button>
      <button type="button" disabled={busy} onClick={() => actions.onEditDescription(v)} className={`${actionButtonClass} text-blue-600 ring-blue-200`}>
        {v.description ? 'Edit description' : 'Add description'}
      </button>
      <button type="button" disabled={busy} onClick={() => actions.onDuplicate(v)} className={`${actionButtonClass} text-zinc-600 ring-zinc-200`}>
        Duplicate
      </button>
      <button type="button" disabled={busy} onClick={() => actions.onDelete(v)} className={`${actionButtonClass} text-red-600 ring-red-200`}>
        Delete
      </button>
    </div>
  );
}

function totalsFor(variants: MarketingVariantStat[]) {
  const totalWeight = variants.reduce((sum, v) => sum + v.weight, 0);
  const activeCount = variants.filter((v) => v.active).length;
  const totalPageViews = variants.reduce((sum, v) => sum + v.pageViews, 0);
  const totalContacts = variants.reduce((sum, v) => sum + v.contactsCreated, 0);
  const totalBookings = variants.reduce((sum, v) => sum + v.bookingsCompleted, 0);
  const totalBookNowClicks = variants.reduce((sum, v) => sum + v.bookNowClicks, 0);
  // The aggregate rate, not an average of the per-variant rates — a variant with 10 views at 50%
  // shouldn't count as much as one with 10,000 views at 2% when rolled up.
  const conversionRate = totalPageViews === 0 ? 0 : totalBookings / totalPageViews;
  return { totalWeight, activeCount, totalPageViews, totalContacts, totalBookings, totalBookNowClicks, conversionRate };
}

export default function VariantTable({ variants, ...actions }: { variants: MarketingVariantStat[] } & VariantActions) {
  if (variants.length === 0) return null;
  const totals = totalsFor(variants);

  return (
    <>
      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {variants.map((v) => (
          <div key={v.variantId} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">{v.name}</span>
              <span className="flex items-center gap-1.5 text-xs text-zinc-500">
                <ActiveToggle v={v} onToggleActive={actions.onToggleActive} busy={actions.busyVariantId === v.variantId} readOnly={actions.readOnly} /> weight {v.weight}
              </span>
            </div>
            <div className="mt-1">
              <Description v={v} />
            </div>
            <dl className="mt-3 grid grid-cols-3 gap-2 text-sm sm:grid-cols-5">
              <div>
                <dt className="text-xs text-zinc-500">Page Views</dt>
                <dd className="tabular-nums">{v.pageViews.toLocaleString('en-US')}</dd>
              </div>
              <div>
                <dt className="text-xs text-zinc-500">Book Clicks</dt>
                <dd className="tabular-nums">{v.bookNowClicks.toLocaleString('en-US')}</dd>
              </div>
              <div>
                <dt className="text-xs text-zinc-500">Contacts</dt>
                <dd className="tabular-nums">{v.contactsCreated.toLocaleString('en-US')}</dd>
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
            {!actions.readOnly && (
              <div className="mt-3 border-t border-zinc-100 pt-3">
                <ActionButtons v={v} actions={actions} />
              </div>
            )}
          </div>
        ))}
        <div className="rounded-lg bg-zinc-50 p-4 ring-1 ring-zinc-200">
          <div className="flex items-center justify-between gap-2">
            <span className="font-semibold">Total</span>
            <span className="text-xs text-zinc-500">{totals.activeCount} of {variants.length} active</span>
          </div>
          <dl className="mt-3 grid grid-cols-3 gap-2 text-sm sm:grid-cols-5">
            <div>
              <dt className="text-xs text-zinc-500">Page Views</dt>
              <dd className="font-semibold tabular-nums">{totals.totalPageViews.toLocaleString('en-US')}</dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Book Clicks</dt>
              <dd className="font-semibold tabular-nums">{totals.totalBookNowClicks.toLocaleString('en-US')}</dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Contacts</dt>
              <dd className="font-semibold tabular-nums">{totals.totalContacts.toLocaleString('en-US')}</dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Bookings</dt>
              <dd className="font-semibold tabular-nums">{totals.totalBookings.toLocaleString('en-US')}</dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Conversion</dt>
              <dd className="font-semibold tabular-nums text-zinc-600">{pct(totals.conversionRate)}</dd>
            </div>
          </dl>
        </div>
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
              <th className="px-3 py-2 text-right">Book Clicks</th>
              <th className="px-3 py-2 text-right">Contacts</th>
              <th className="px-3 py-2 text-right">Bookings</th>
              <th className="px-3 py-2 text-right">Conversion %</th>
              <th className="px-3 py-2">Link</th>
              {!actions.readOnly && <th className="px-3 py-2">Actions</th>}
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {variants.map((v) => (
              <tr key={v.variantId} className="hover:bg-zinc-50">
                <td className="px-3 py-2">
                  <div className="font-medium">{v.name}</div>
                  <Description v={v} />
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{v.weight}</td>
                <td className="px-3 py-2 text-center">
                  <ActiveToggle v={v} onToggleActive={actions.onToggleActive} busy={actions.busyVariantId === v.variantId} readOnly={actions.readOnly} />
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{v.pageViews.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{v.bookNowClicks.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{v.contactsCreated.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums">{v.bookingsCompleted.toLocaleString('en-US')}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-500">{pct(v.conversionRate)}</td>
                <td className="px-3 py-2">
                  {v.deepLinkUrl ? <VariantLinkButton url={v.deepLinkUrl} /> : <span className="text-zinc-300">—</span>}
                </td>
                {!actions.readOnly && (
                  <td className="px-3 py-2">
                    <ActionButtons v={v} actions={actions} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-zinc-200 bg-zinc-50 font-semibold">
              <td className="px-3 py-2">Total</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalWeight}</td>
              <td className="px-3 py-2 text-center text-xs font-normal text-zinc-500">
                {totals.activeCount} of {variants.length} active
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalPageViews.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalBookNowClicks.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalContacts.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalBookings.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{pct(totals.conversionRate)}</td>
              <td className="px-3 py-2" />
              {!actions.readOnly && <td className="px-3 py-2" />}
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  );
}
