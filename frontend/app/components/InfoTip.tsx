'use client';

import { useEffect, useLayoutEffect, useRef, useState } from 'react';

// A small info "ⓘ" that reveals its text on click/tap (and keyboard) — not just hover, so it works on
// mobile. Closes on outside click or Escape. Replaces the native `title` attribute, which never shows
// on touch devices. The popover is shifted horizontally on open so it never runs off the screen edge
// (which on a phone caused the line — e.g. the tier-bonus note — to push out of view).
export function InfoTip({ text, label = 'More info' }: { text: string; label?: string }) {
  const [open, setOpen] = useState(false);
  const [shift, setShift] = useState(0);
  const ref = useRef<HTMLSpanElement>(null);
  const popRef = useRef<HTMLSpanElement>(null);

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

  // Keep the popover on screen: measure before paint and nudge it left/right to fit the viewport.
  useLayoutEffect(() => {
    if (!open) { setShift(0); return; }
    const el = popRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const margin = 8;
    let s = 0;
    if (r.right > window.innerWidth - margin) s = window.innerWidth - margin - r.right; // overflowing right → move left
    if (r.left + s < margin) s = margin - r.left; // don't push it off the left either
    setShift(s);
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
          ref={popRef}
          role="tooltip"
          style={{ transform: shift ? `translateX(${shift}px)` : undefined }}
          className="absolute left-0 top-full z-30 mt-1 block w-64 max-w-[88vw] rounded-md bg-zinc-800 px-3 py-2 text-left text-xs font-normal leading-relaxed text-white shadow-xl ring-1 ring-white/15"
        >
          {text}
        </span>
      )}
    </span>
  );
}
