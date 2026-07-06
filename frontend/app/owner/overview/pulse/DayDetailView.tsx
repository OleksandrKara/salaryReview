import type { RevenueDayDetail } from '../../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

function formatDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
}

export default function DayDetailView({ detail }: { detail: RevenueDayDetail }) {
  if (!detail.hasSnapshot) {
    return (
      <div className="rounded-2xl border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500">
        No revenue snapshot was captured for {formatDate(detail.date)} — this is only available for
        dates after daily snapshots started, and there may be an occasional gap.
      </div>
    );
  }

  const { mtdRevenue, mtdCard, mtdCash, mtdServices, upcomingCount, upcomingGross, projectedMid, projectedLow, projectedHigh, monthEndActual } = detail;
  const hasRange = projectedLow != null && projectedHigh != null;
  const settled = monthEndActual != null;
  const accuracyPct =
    settled && projectedMid ? Math.round((1 - Math.abs((monthEndActual as number) - projectedMid) / (monthEndActual as number)) * 100) : null;

  return (
    <div className="space-y-4">
      <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">{formatDate(detail.date)}</p>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-2xl border border-zinc-200 bg-white p-5">
          <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">Made so far that day</p>
          <div className="mt-2 text-3xl font-semibold tabular-nums text-zinc-900">{usd(mtdRevenue ?? 0)}</div>
          <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1.5 text-sm">
            <span className="flex items-center gap-2 text-zinc-600">
              <span className="inline-block h-2 w-2 rounded-full bg-indigo-500" />
              Card <span className="font-semibold tabular-nums text-zinc-800">{usd(mtdCard ?? 0)}</span>
            </span>
            <span className="flex items-center gap-2 text-zinc-600">
              <span className="inline-block h-2 w-2 rounded-full bg-emerald-500" />
              Cash <span className="font-semibold tabular-nums text-zinc-800">{usd(mtdCash ?? 0)}</span>
            </span>
          </div>
          <p className="mt-2 text-xs text-zinc-400">{mtdServices} service{mtdServices === 1 ? '' : 's'} so far that month</p>
          {upcomingCount > 0 ? (
            <p className="mt-1 text-xs text-zinc-400">
              {upcomingCount} upcoming booking{upcomingCount === 1 ? '' : 's'} · {usd(upcomingGross ?? 0)}
            </p>
          ) : null}
        </div>

        <div className="rounded-2xl border border-zinc-200 bg-white p-5">
          <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">Projected that day</p>
          <div className="mt-2 text-3xl font-semibold tabular-nums text-zinc-900">
            {projectedMid != null ? `~${usd(projectedMid)}` : '—'}
          </div>
          {hasRange ? (
            <p className="mt-2 text-xs text-zinc-400">
              Range <span className="font-medium text-zinc-600">{usd(projectedLow!)} – {usd(projectedHigh!)}</span>
            </p>
          ) : (
            <p className="mt-2 text-xs text-zinc-400">Not enough history yet for a range estimate.</p>
          )}
        </div>
      </div>

      {settled ? (
        <div className="rounded-2xl border border-zinc-200 bg-zinc-50 p-5">
          <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">How the projection held up</p>
          <div className="mt-2 flex flex-wrap items-baseline gap-x-6 gap-y-2">
            <span className="text-sm text-zinc-600">
              Projected <span className="font-semibold tabular-nums text-zinc-900">~{usd(projectedMid ?? 0)}</span>
            </span>
            <span className="text-sm text-zinc-600">
              Month actually finished at{' '}
              <span className="font-semibold tabular-nums text-zinc-900">{usd(monthEndActual as number)}</span>
            </span>
            {accuracyPct != null ? (
              <span className="text-sm font-medium text-zinc-700">{accuracyPct}% accurate</span>
            ) : null}
          </div>
        </div>
      ) : (
        <p className="text-xs text-zinc-400">This month hasn&apos;t closed yet, so there&apos;s no final total to compare against.</p>
      )}
    </div>
  );
}
