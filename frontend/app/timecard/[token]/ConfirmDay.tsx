'use client';

import { useEffect, useState } from 'react';
import { hhmm, hrs, type DayView } from '../../lib/timecards';

export default function ConfirmDay({ token }: { token: string }) {
  const [day, setDay] = useState<DayView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rests, setRests] = useState(true);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    fetch(`/api/public-timecards/${encodeURIComponent(token)}`)
      .then(async (r) => {
        if (!r.ok) throw new Error(r.status === 404 ? 'This link has expired. Open the salon app to confirm your hours.' : 'Could not load your hours.');
        setDay((await r.json()) as DayView);
      })
      .catch((e: Error) => setError(e.message));
  }, [token]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const r = await fetch(`/api/public-timecards/${encodeURIComponent(token)}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ restsTaken: rests }),
      });
      if (!r.ok) {
        let msg = 'Could not confirm. Open the salon app to edit your hours.';
        try {
          msg = (await r.json()).message || msg;
        } catch {
          /* not JSON */
        }
        throw new Error(msg);
      }
      setDone(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (error && !day) return <p className="rounded-lg bg-red-50 p-4 text-sm text-red-800">{error}</p>;
  if (!day) return <p className="text-sm text-gray-500">Loading...</p>;
  if (done || day.status === 'WORKER_CONFIRMED' || day.status === 'APPROVED') {
    return <p className="rounded-lg bg-green-50 p-4 text-sm text-green-800">Thank you, your hours for {day.date} are confirmed.</p>;
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Confirm your hours</h1>
      <p className="text-sm text-gray-600">{day.date}</p>
      {day.timecards.map((c, i) => (
        <div key={i} className="rounded-lg border p-3 text-sm">
          <div className="font-medium">
            {hhmm(c.start)} to {hhmm(c.end)}
          </div>
          {c.breaks.map((b, j) => (
            <div key={j} className="text-gray-600">
              {b.kind === 'UNPAID' ? 'Meal break' : 'Rest break'} {hhmm(b.start)} to {hhmm(b.end)}
            </div>
          ))}
        </div>
      ))}
      <p className="text-sm">Paid time: {hrs(day.paidMinutes)} h</p>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" checked={rests} onChange={(e) => setRests(e.target.checked)} className="mt-1" />
        <span>I was able to take my rest breaks.</span>
      </label>
      {error && <p className="text-sm text-red-700">{error}</p>}
      <button onClick={confirm} disabled={busy} className="w-full rounded-lg bg-black px-4 py-3 text-white disabled:opacity-50">
        {busy ? 'Saving...' : 'These hours are correct'}
      </button>
      <p className="text-xs text-gray-500">Something is off? Open the salon app, My hours, and edit the day.</p>
    </div>
  );
}
