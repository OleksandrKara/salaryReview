import type { RetentionSeriesPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Stacked-bar chart of new (accent) vs returning (zinc) clients per month. Pure render (no chart lib);
// mobile-friendly — bars flex to fill, scroll horizontally only if the range is very long. `newLabel`
// lets a salon-level view read "Fresh vs returning" while the provider view reads "New vs returning".
export default function NewReturningChart({
  points,
  label,
  newLabel = 'New',
  testId = 'retention-chart',
}: {
  points: RetentionSeriesPoint[];
  label: string;
  newLabel?: string;
  testId?: string;
}) {
  const max = Math.max(1, ...points.map((p) => p.clientsSeen));
  const totalNew = points.reduce((s, p) => s + p.newClients, 0);
  const totalRet = points.reduce((s, p) => s + p.returningClients, 0);
  const newWord = newLabel.toLowerCase();

  // Up to ~12 months fit a phone width; beyond that, let the chart scroll horizontally rather than
  // squeezing bars into slivers.
  const scrolls = points.length > 12;

  return (
    <div data-testid={testId} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="text-sm font-medium text-zinc-700">{newLabel} vs returning clients — {label}</span>
        <span className="flex items-center gap-3 text-xs text-zinc-500">
          <Legend className="bg-emerald-500" text={`${newLabel} (${totalNew})`} />
          <Legend className="bg-zinc-300" text={`Returning (${totalRet})`} />
        </span>
      </div>

      <div className={scrolls ? '-mx-1 overflow-x-auto px-1' : ''}>
        <div
          className="flex items-end gap-[2px] sm:gap-1"
          style={scrolls ? { minWidth: `${points.length * 26}px` } : undefined}
        >
          {points.map((p, i) => {
            const total = p.newClients + p.returningClients;
            return (
              <div key={`${p.year}-${p.month}`} className="flex flex-1 flex-col items-center gap-1">
                <div
                  className="flex h-32 w-full max-w-[2.75rem] flex-col justify-end overflow-hidden rounded-t sm:h-40"
                  title={`${MONTHS[p.month - 1]} ${p.year} · ${p.newClients} ${newWord} · ${p.returningClients} returning · ${total} total`}
                >
                  <div className="w-full bg-emerald-500" style={{ height: `${(p.newClients / max) * 100}%` }} />
                  <div className="w-full bg-zinc-300" style={{ height: `${(p.returningClients / max) * 100}%` }} />
                </div>
                <span className="text-center text-[9px] leading-tight text-zinc-400">
                  {MONTHS[p.month - 1]}
                  {p.month === 1 || i === 0 ? (
                    <span className="block text-[8px] text-zinc-300">{`’${String(p.year).slice(2)}`}</span>
                  ) : null}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function Legend({ className, text }: { className: string; text: string }) {
  return (
    <span className="flex items-center gap-1">
      <span className={`inline-block h-2.5 w-2.5 rounded-sm ${className}`} /> {text}
    </span>
  );
}
