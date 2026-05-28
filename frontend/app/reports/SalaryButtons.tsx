'use client';

import { useEffect, useState } from 'react';
import SalaryCopyButton from '../components/SalaryCopyButton';

// Two small per-half buttons on the report row; clicking one opens a popup with that half's
// copy-pasteable #salary block. The messages are already in the row data, so the popup is instant.
export default function SalaryButtons({
  name,
  firstHalfMessage,
  secondHalfMessage,
}: {
  name: string;
  firstHalfMessage: string | null;
  secondHalfMessage: string | null;
}) {
  const [open, setOpen] = useState<null | { label: string; message: string }>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(null);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const btn = 'rounded px-1.5 py-0.5 text-xs font-medium ring-1 ring-zinc-300 text-zinc-600 hover:bg-zinc-100 disabled:opacity-40 disabled:hover:bg-transparent';

  return (
    <>
      <div className="flex gap-1">
        <button className={btn} disabled={!firstHalfMessage}
          onClick={() => firstHalfMessage && setOpen({ label: '1–15', message: firstHalfMessage })}>
          1–15
        </button>
        <button className={btn} disabled={!secondHalfMessage}
          onClick={() => secondHalfMessage && setOpen({ label: '16–END', message: secondHalfMessage })}>
          16–END
        </button>
      </div>

      {open && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
          onClick={() => setOpen(null)}
        >
          <div
            className="w-full max-w-md rounded-lg bg-white p-4 shadow-xl ring-1 ring-zinc-200"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">{name} · {open.label}</h3>
              <button onClick={() => setOpen(null)} className="text-zinc-400 hover:text-zinc-700" aria-label="Close">✕</button>
            </div>
            <SalaryCopyButton message={open.message} />
          </div>
        </div>
      )}
    </>
  );
}
