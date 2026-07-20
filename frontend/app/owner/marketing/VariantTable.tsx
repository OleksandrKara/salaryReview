import { useState } from 'react';
import type { MarketingVariantStat } from '../../lib/types';
import VariantLinkButton from './VariantLinkButton';

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

const actionButtonClass = 'rounded px-2 py-0.5 text-xs font-medium ring-1 hover:bg-zinc-50';

interface VariantActions {
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

// Bookings/Conversion always show the tracked-only number first (bold, primary) — the
// follow-up line only renders when there's actually something to add, so a variant/page with no
// manager follow-ups looks exactly like it did before this feature existed.
function BookingsValue({ tracked, followUp, size = 'sm' }: { tracked: number; followUp: number; size?: 'sm' | 'base' }) {
  const primaryClass = size === 'base' ? 'font-semibold tabular-nums' : 'tabular-nums';
  return (
    <>
      <div className={primaryClass}>{tracked.toLocaleString('en-US')}</div>
      {followUp > 0 && (
        <div className="whitespace-nowrap text-xs font-medium text-amber-700">
          +{followUp.toLocaleString('en-US')} follow-up &rarr; {(tracked + followUp).toLocaleString('en-US')}
        </div>
      )}
    </>
  );
}

function ConversionValue({ rate, adjustedRate, followUp }: { rate: number; adjustedRate: number; followUp: number }) {
  return (
    <>
      <div className="tabular-nums">{pct(rate)}</div>
      {followUp > 0 && (
        <div className="whitespace-nowrap text-xs font-medium text-amber-700">&rarr; {pct(adjustedRate)} incl. follow-up</div>
      )}
    </>
  );
}

// Of all page views, what share clicked "Book now" / became a contact — the two rate metrics live
// as a muted sub-line under the existing raw count (no new columns, so the table stays exactly as
// wide as before). The leading in-rotation variant for each rate is picked out in green so which
// variant is winning reads at a glance, without having to scan and compare numbers down a column.
const bookClickRate = (v: MarketingVariantStat) => (v.pageViews === 0 ? 0 : v.bookNowClicks / v.pageViews);
const contactRate = (v: MarketingVariantStat) => (v.pageViews === 0 ? 0 : v.contactsCreated / v.pageViews);

// Only declares a leader when there are at least two comparable (in-rotation, view-having)
// variants — with just one, "winning" is meaningless and no highlight should show.
function bestVariantId(variants: MarketingVariantStat[], rate: (v: MarketingVariantStat) => number): string | null {
  const candidates = variants.filter((v) => v.weight > 0 && v.pageViews > 0);
  if (candidates.length < 2) return null;
  return candidates.reduce((best, v) => (rate(v) > rate(best) ? v : best)).variantId;
}

function RateSubline({ rate, isLeader }: { rate: number; isLeader: boolean }) {
  return (
    <div className={`tabular-nums text-xs ${isLeader ? 'font-semibold text-emerald-600' : 'text-zinc-400'}`}>
      {pct(rate)}
    </div>
  );
}

// Only shown at all when at least one variant actually has a follow-up booking — otherwise this
// whole feature stays invisible, exactly like before it existed.
function FollowUpExplainer() {
  return (
    <div className="mb-3 rounded-lg bg-amber-50 p-3 text-xs leading-relaxed text-amber-900 ring-1 ring-amber-200">
      <span className="font-semibold">Some clients book after a manager follow-up call</span>, not through our
      online flow — they leave their info, don&apos;t book right away, and a manager later books them directly
      in Square. Our automatic tracking misses those, so we find them separately (the same way the Contacts tab&apos;s
      &quot;Sync appointments&quot; does). Numbers marked <span className="font-semibold text-amber-700">+N follow-up</span> below
      include them on top of the regular tracked count.
    </div>
  );
}

function totalsFor(variants: MarketingVariantStat[]) {
  const totalWeight = variants.reduce((sum, v) => sum + v.weight, 0);
  const totalPageViews = variants.reduce((sum, v) => sum + v.pageViews, 0);
  const totalContacts = variants.reduce((sum, v) => sum + v.contactsCreated, 0);
  const totalBookings = variants.reduce((sum, v) => sum + v.bookingsCompleted, 0);
  const totalFollowUpBookings = variants.reduce((sum, v) => sum + v.followUpBookings, 0);
  const totalBookNowClicks = variants.reduce((sum, v) => sum + v.bookNowClicks, 0);
  // The aggregate rate, not an average of the per-variant rates — a variant with 10 views at 50%
  // shouldn't count as much as one with 10,000 views at 2% when rolled up.
  const conversionRate = totalPageViews === 0 ? 0 : totalBookings / totalPageViews;
  const adjustedConversionRate = totalPageViews === 0 ? 0 : (totalBookings + totalFollowUpBookings) / totalPageViews;
  const totalBookClickRate = totalPageViews === 0 ? 0 : totalBookNowClicks / totalPageViews;
  const totalContactRate = totalPageViews === 0 ? 0 : totalContacts / totalPageViews;
  return {
    totalWeight, totalPageViews, totalContacts, totalBookings,
    totalFollowUpBookings, totalBookNowClicks, conversionRate, adjustedConversionRate,
    totalBookClickRate, totalContactRate,
  };
}

function MobileCard({
  v,
  actions,
  bestBookClickId,
  bestContactId,
}: {
  v: MarketingVariantStat;
  actions: VariantActions;
  bestBookClickId: string | null;
  bestContactId: string | null;
}) {
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">{v.name}</span>
        <span className="text-xs text-zinc-500">weight {v.weight}</span>
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
          {v.pageViews > 0 && <RateSubline rate={bookClickRate(v)} isLeader={v.variantId === bestBookClickId} />}
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Contacts</dt>
          <dd className="tabular-nums">{v.contactsCreated.toLocaleString('en-US')}</dd>
          {v.pageViews > 0 && <RateSubline rate={contactRate(v)} isLeader={v.variantId === bestContactId} />}
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Bookings</dt>
          <dd><BookingsValue tracked={v.bookingsCompleted} followUp={v.followUpBookings} /></dd>
        </div>
        <div>
          <dt className="text-xs text-zinc-500">Conversion</dt>
          <dd className="text-zinc-500">
            <ConversionValue rate={v.conversionRate} adjustedRate={v.adjustedConversionRate} followUp={v.followUpBookings} />
          </dd>
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
  );
}

function DesktopRow({
  v,
  actions,
  bestBookClickId,
  bestContactId,
}: {
  v: MarketingVariantStat;
  actions: VariantActions;
  bestBookClickId: string | null;
  bestContactId: string | null;
}) {
  return (
    <tr className="hover:bg-zinc-50">
      <td className="px-3 py-2">
        <div className="font-medium">{v.name}</div>
        <Description v={v} />
      </td>
      <td className="px-3 py-2 text-right tabular-nums">{v.weight}</td>
      <td className="px-3 py-2 text-right tabular-nums">{v.pageViews.toLocaleString('en-US')}</td>
      <td className="px-3 py-2 text-right tabular-nums">
        {v.bookNowClicks.toLocaleString('en-US')}
        {v.pageViews > 0 && <RateSubline rate={bookClickRate(v)} isLeader={v.variantId === bestBookClickId} />}
      </td>
      <td className="px-3 py-2 text-right tabular-nums">
        {v.contactsCreated.toLocaleString('en-US')}
        {v.pageViews > 0 && <RateSubline rate={contactRate(v)} isLeader={v.variantId === bestContactId} />}
      </td>
      <td className="px-3 py-2 text-right"><BookingsValue tracked={v.bookingsCompleted} followUp={v.followUpBookings} /></td>
      <td className="px-3 py-2 text-right text-zinc-500">
        <ConversionValue rate={v.conversionRate} adjustedRate={v.adjustedConversionRate} followUp={v.followUpBookings} />
      </td>
      <td className="px-3 py-2">
        {v.deepLinkUrl ? <VariantLinkButton url={v.deepLinkUrl} /> : <span className="text-zinc-300">—</span>}
      </td>
      {!actions.readOnly && (
        <td className="px-3 py-2">
          <ActionButtons v={v} actions={actions} />
        </td>
      )}
    </tr>
  );
}

// A weight-0 variant never enters the random A/B pool a new visitor is assigned into — it's only
// still reachable through its own deep link (see VariantLinkButton) or a returning visitor's
// existing cookie. Grouping it away from the in-rotation variants makes that distinction obvious
// at a glance, instead of a "0" quietly sitting in the same list as variants actually being tested.
function GroupHeading({ label, colSpan }: { label: string; colSpan?: number }) {
  if (colSpan) {
    return (
      <tr>
        <td colSpan={colSpan} className="bg-zinc-50 px-3 py-1.5 text-xs font-medium uppercase tracking-wide text-zinc-500">
          {label}
        </td>
      </tr>
    );
  }
  return <p className="mb-2 mt-1 text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</p>;
}

export default function VariantTable({ variants, ...actions }: { variants: MarketingVariantStat[] } & VariantActions) {
  // Collapsed by default: a variant at weight 0 is out of rotation (often permanently — an old
  // test that's done, or one only kept alive for its own deep link), so there's usually nothing
  // actionable about it day to day. Hiding these rows unless asked for also means they're not
  // rendered at all by default — real savings once a page has accumulated a dozen retired
  // variants, not just a visual collapse.
  const [showInactive, setShowInactive] = useState(false);

  if (variants.length === 0) return null;
  const totals = totalsFor(variants);
  const anyFollowUp = totals.totalFollowUpBookings > 0;

  const inRotation = variants.filter((v) => v.weight > 0);
  const noWeight = variants.filter((v) => v.weight === 0);
  const hasInactive = noWeight.length > 0;
  const bestBookClickId = bestVariantId(variants, bookClickRate);
  const bestContactId = bestVariantId(variants, contactRate);
  // Only worth labeling the two groups when both actually exist — a page with every variant in (or
  // out of) rotation should look exactly like it did before this grouping existed.
  const showGroups = inRotation.length > 0 && hasInactive;
  const colCount = 8 + (actions.readOnly ? 0 : 1);

  return (
    <>
      {anyFollowUp && <FollowUpExplainer />}

      {/* Mobile cards */}
      <div className="flex flex-col gap-3 sm:hidden">
        {showGroups && <GroupHeading label="In rotation" />}
        {inRotation.map((v) => (
          <MobileCard key={v.variantId} v={v} actions={actions} bestBookClickId={bestBookClickId} bestContactId={bestContactId} />
        ))}
        {showGroups && showInactive && <GroupHeading label="Not in rotation (weight 0)" />}
        {showInactive && noWeight.map((v) => (
          <MobileCard key={v.variantId} v={v} actions={actions} bestBookClickId={bestBookClickId} bestContactId={bestContactId} />
        ))}
        <div className="rounded-lg bg-zinc-50 p-4 ring-1 ring-zinc-200">
          <div className="flex items-center justify-between gap-2">
            <span className="font-semibold">Total</span>
            <span className="text-xs text-zinc-500">{variants.length} variant{variants.length === 1 ? '' : 's'}</span>
          </div>
          <dl className="mt-3 grid grid-cols-3 gap-2 text-sm sm:grid-cols-5">
            <div>
              <dt className="text-xs text-zinc-500">Page Views</dt>
              <dd className="font-semibold tabular-nums">{totals.totalPageViews.toLocaleString('en-US')}</dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Book Clicks</dt>
              <dd className="font-semibold tabular-nums">{totals.totalBookNowClicks.toLocaleString('en-US')}</dd>
              {totals.totalPageViews > 0 && <div className="tabular-nums text-xs text-zinc-400">{pct(totals.totalBookClickRate)}</div>}
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Contacts</dt>
              <dd className="font-semibold tabular-nums">{totals.totalContacts.toLocaleString('en-US')}</dd>
              {totals.totalPageViews > 0 && <div className="tabular-nums text-xs text-zinc-400">{pct(totals.totalContactRate)}</div>}
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Bookings</dt>
              <dd><BookingsValue tracked={totals.totalBookings} followUp={totals.totalFollowUpBookings} size="base" /></dd>
            </div>
            <div>
              <dt className="text-xs text-zinc-500">Conversion</dt>
              <dd className="font-semibold text-zinc-600">
                <ConversionValue rate={totals.conversionRate} adjustedRate={totals.adjustedConversionRate} followUp={totals.totalFollowUpBookings} />
              </dd>
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
            {showGroups && <GroupHeading label="In rotation" colSpan={colCount} />}
            {inRotation.map((v) => (
              <DesktopRow key={v.variantId} v={v} actions={actions} bestBookClickId={bestBookClickId} bestContactId={bestContactId} />
            ))}
            {showGroups && showInactive && <GroupHeading label="Not in rotation (weight 0)" colSpan={colCount} />}
            {showInactive && noWeight.map((v) => (
              <DesktopRow key={v.variantId} v={v} actions={actions} bestBookClickId={bestBookClickId} bestContactId={bestContactId} />
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-zinc-200 bg-zinc-50 font-semibold">
              <td className="px-3 py-2">Total</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalWeight}</td>
              <td className="px-3 py-2 text-right tabular-nums">{totals.totalPageViews.toLocaleString('en-US')}</td>
              <td className="px-3 py-2 text-right tabular-nums">
                {totals.totalBookNowClicks.toLocaleString('en-US')}
                {totals.totalPageViews > 0 && <div className="text-xs font-normal text-zinc-400">{pct(totals.totalBookClickRate)}</div>}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {totals.totalContacts.toLocaleString('en-US')}
                {totals.totalPageViews > 0 && <div className="text-xs font-normal text-zinc-400">{pct(totals.totalContactRate)}</div>}
              </td>
              <td className="px-3 py-2 text-right"><BookingsValue tracked={totals.totalBookings} followUp={totals.totalFollowUpBookings} size="base" /></td>
              <td className="px-3 py-2 text-right text-zinc-600">
                <ConversionValue rate={totals.conversionRate} adjustedRate={totals.adjustedConversionRate} followUp={totals.totalFollowUpBookings} />
              </td>
              <td className="px-3 py-2" />
              {!actions.readOnly && <td className="px-3 py-2" />}
            </tr>
          </tfoot>
        </table>
      </div>

      {hasInactive && (
        <button
          type="button"
          onClick={() => setShowInactive((v) => !v)}
          className="mt-2 flex w-full items-center justify-center gap-1.5 rounded-lg py-2 text-xs font-medium text-zinc-500 ring-1 ring-zinc-200 hover:bg-zinc-50 hover:text-zinc-700"
        >
          {showInactive
            ? 'Hide inactive variants'
            : `Show ${noWeight.length} inactive variant${noWeight.length === 1 ? '' : 's'} (weight 0)`}
          <span aria-hidden>{showInactive ? '▴' : '▾'}</span>
        </button>
      )}
    </>
  );
}
