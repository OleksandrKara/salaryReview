'use client';

import { useEffect, useRef, useState } from 'react';

// A small info "ⓘ" that reveals its text on click/tap (and keyboard) — not just hover, so it works on
// mobile. Closes on outside click or Escape. Replaces the native `title` attribute, which never shows
// on touch devices.
export function InfoTip({ text, label = 'More info' }: { text: string; label?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <span ref={ref} className="relative inline-block align-middle">
      <button
        type="button"
        aria-label={label}
        aria-expanded={open}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); setOpen((o) => !o); }}
        className="ml-0.5 cursor-help leading-none text-zinc-400 hover:text-zinc-600"
      >
        ⓘ
      </button>
      {open && (
        <span
          role="tooltip"
          className="absolute left-0 top-full z-30 mt-1 block w-56 max-w-[70vw] rounded-md bg-zinc-800 px-3 py-2 text-left text-xs font-normal leading-relaxed text-white shadow-xl ring-1 ring-white/15"
        >
          {text}
        </span>
      )}
    </span>
  );
}
