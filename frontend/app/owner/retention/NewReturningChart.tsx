import type { RetentionSeriesPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Stacked-bar chart of new (accent) vs returning (zinc) clients per month. Pure render (no chart lib);
// mobile-friendly — bars flex to fill, scroll horizontally only if the range is very long.
export default function NewReturningChart({ points, label }: { points: RetentionSeriesPoint[]; label: string }) {
  const max = Math.max(1, ...points.map((p) => p.clientsSeen));
  const totalNew = points.reduce((s, p) => s + p.newClients, 0);
  const totalRet = points.reduce((s, p) => s + p.returningClients, 0);

  return (
    <div className="rounded-xl bg-white p-4 ring-1 ring-zinc-200">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium text-zinc-700">New vs returning clients — {label}</span>
        <span className="flex items-center gap-3 text-xs text-zinc-500">
          <Legend className="bg-emerald-500" text={`New (${totalNew})`} />
          <Legend className="bg-zinc-300" text={`Returning (${totalRet})`} />
        </span>
      </div>

      <div className="overflow-x-auto">
        <div className="flex min-w-full items-end gap-1" style={{ minWidth: `${points.length * 28}px` }}>
          {points.map((p) => {
            const total = p.newClients + p.returningClients;
            return (
              <div key={`${p.year}-${p.month}`} className="flex flex-1 flex-col items-center gap-1">
                <div
                  className="flex h-40 w-full max-w-[2.5rem] flex-col justify-end overflow-hidden rounded-t"
                  title={`${MONTHS[p.month - 1]} ${p.year} · ${p.newClients} new · ${p.returningClients} returning · ${total} total`}
                >
                  <div className="w-full bg-emerald-500" style={{ height: `${(p.newClients / max) * 100}%` }} />
                  <div className="w-full bg-zinc-300" style={{ height: `${(p.returningClients / max) * 100}%` }} />
                </div>
                <span className="text-[9px] leading-tight text-zinc-400">
                  {MONTHS[p.month - 1]}
                  {p.month === 1 || points.indexOf(p) === 0 ? (
                    <span className="block text-center text-[8px] text-zinc-300">{`’${String(p.year).slice(2)}`}</span>
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
