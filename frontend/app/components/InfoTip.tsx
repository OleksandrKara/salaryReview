'use client';

import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

// A small info "ⓘ" that reveals its text on click/tap (and keyboard) — not just hover, so it works on
// mobile. The popover is rendered in a portal with fixed positioning, so it sits on top of everything
// and is never clipped by an ancestor's overflow (e.g. a scrollable table) — and it's clamped to the
// viewport so it can't run off the screen edge. Closes on outside click, Escape, or scroll/resize.
export function InfoTip({ text, label = 'More info' }: { text: string; label?: string }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const popRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (btnRef.current?.contains(e.target as Node) || popRef.current?.contains(e.target as Node)) return;
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const onMove = () => setOpen(false); // close on scroll/resize — a fixed popover would otherwise drift
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onMove, true);
    window.addEventListener('resize', onMove);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onMove, true);
      window.removeEventListener('resize', onMove);
    };
  }, [open]);

  // Place the popover under the icon, clamped to the viewport, before paint.
  useLayoutEffect(() => {
    if (!open || !btnRef.current) { setPos(null); return; }
    const b = btnRef.current.getBoundingClientRect();
    const margin = 8;
    const width = Math.min(288, window.innerWidth - margin * 2);
    let left = b.left;
    if (left + width > window.innerWidth - margin) left = window.innerWidth - margin - width;
    if (left < margin) left = margin;
    setPos({ top: b.bottom + 6, left, width });
  }, [open]);

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        aria-label={label}
        aria-expanded={open}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); setOpen((o) => !o); }}
        className="ml-0.5 inline cursor-help align-middle leading-none text-zinc-400 hover:text-zinc-600"
      >
        ⓘ
      </button>
      {open && pos && typeof document !== 'undefined' && createPortal(
        <div
          ref={popRef}
          role="tooltip"
          style={{ position: 'fixed', top: pos.top, left: pos.left, width: pos.width }}
          className="z-50 rounded-md bg-zinc-800 px-3 py-2 text-left text-xs font-normal leading-relaxed text-white shadow-xl ring-1 ring-white/15"
        >
          {text}
        </div>,
        document.body,
      )}
    </>
  );
}
