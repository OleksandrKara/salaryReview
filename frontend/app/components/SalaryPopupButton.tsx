'use client';

import { useEffect, useState } from 'react';
import SalaryCopyButton from './SalaryCopyButton';

// Small "#salary" button that opens a popup with the copy-pasteable block for one period.
export default function SalaryPopupButton({ title, message }: { title: string; message: string }) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="rounded px-2 py-0.5 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50"
      >
        #salary
      </button>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" onClick={() => setOpen(false)}>
          <div className="w-full max-w-md rounded-lg bg-white p-4 shadow-xl ring-1 ring-zinc-200" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">{title}</h3>
              <button onClick={() => setOpen(false)} className="text-zinc-400 hover:text-zinc-700" aria-label="Close">✕</button>
            </div>
            <SalaryCopyButton message={message} />
          </div>
        </div>
      )}
    </>
  );
}
