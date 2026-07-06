'use client';

import { useRouter, useSearchParams } from 'next/navigation';

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// A single date input that navigates to ?day=<value> the moment you pick one — no submit button,
// no extra click. Clearing it drops back to the month-to-date view above.
export default function DayPicker() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const day = searchParams.get('day') ?? '';

  function go(value: string) {
    const params = new URLSearchParams(searchParams.toString());
    if (value) params.set('day', value);
    else params.delete('day');
    router.push(`/owner/overview/pulse?${params.toString()}`);
  }

  return (
    <div className="rounded-2xl border border-zinc-200 bg-white p-5 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-zinc-400">Look up a specific day</p>
          <p className="mt-0.5 text-xs text-zinc-400">See what was made and projected as of any past date.</p>
        </div>
        <div className="flex items-center gap-2">
          <input
            type="date"
            value={day}
            max={isoDate(new Date())}
            onChange={(e) => go(e.target.value)}
            className="rounded-lg border border-zinc-300 px-3 py-2 text-sm"
          />
          {day ? (
            <button
              type="button"
              onClick={() => go('')}
              className="rounded-lg px-2 py-2 text-xs font-medium text-zinc-500 hover:bg-zinc-100"
            >
              Clear
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
