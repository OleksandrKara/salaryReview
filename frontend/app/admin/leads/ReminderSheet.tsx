'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../../lib/i18n';
import type { Language } from '../../lib/types';
import ReminderFields, { btnPrimary, btnQuiet } from './ReminderFields';
import { DEFAULT_DAYS, DEFAULT_TIME, addDays, choiceBody, choiceInPast, ptYmd, type ReminderChoice } from './reminders';

export type ReachedOutBody = { days?: number; date?: string; time?: string; note?: string; none?: boolean };

const CLOSE_DISTANCE = 90;

function Sheet({ title, subtitle, language, onClose, onSubmit }: {
  title: string; subtitle: string; language: Language | null; onClose: () => void; onSubmit: (body: ReachedOutBody) => Promise<boolean>;
}) {
  const [now] = useState(() => Date.now());
  const [choice, setChoice] = useState<ReminderChoice>({ days: DEFAULT_DAYS, date: addDays(ptYmd(now), DEFAULT_DAYS), time: DEFAULT_TIME, timeSet: false });
  const [note, setNote] = useState('');
  const [none, setNone] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [drag, setDrag] = useState(0);
  const panel = useRef<HTMLDivElement>(null);
  const startY = useRef<number | null>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const first = panel.current?.querySelector<HTMLElement>('[data-chip-selected]') ?? panel.current;
    first?.focus();
    return () => { document.body.style.overflow = overflow; before?.focus?.(); };
  }, []);

  async function submit() {
    if (busy) return;
    if (!none && choiceInPast(choice, now)) { setError(t(language, 'remPast')); return; }
    setBusy(true);
    setError('');
    const ok = await onSubmit(none ? { none: true } : choiceBody(choice, note));
    setBusy(false);
    if (ok) onClose(); else setError(t(language, 'remErr'));
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === 'Escape') { e.stopPropagation(); onClose(); return; }
    const el = e.target as HTMLElement;
    const plain = el.tagName !== 'TEXTAREA' && (el.tagName !== 'BUTTON' || el.getAttribute('role') === 'radio');
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey || plain)) {
      e.preventDefault();
      void submit();
      return;
    }
    if (e.key === 'Tab' && panel.current) {
      const f = Array.from(panel.current.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex="0"]')).filter((x) => x.offsetParent !== null);
      if (f.length === 0) return;
      const a = document.activeElement;
      if (e.shiftKey && (a === f[0] || a === panel.current)) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && a === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
    }
  }

  const handle = {
    onPointerDown: (e: React.PointerEvent) => { startY.current = e.clientY; e.currentTarget.setPointerCapture(e.pointerId); },
    onPointerMove: (e: React.PointerEvent) => { if (startY.current != null) setDrag(Math.max(0, e.clientY - startY.current)); },
    onPointerUp: () => { const d = drag; startY.current = null; setDrag(0); if (d > CLOSE_DISTANCE) onClose(); },
    onPointerCancel: () => { startY.current = null; setDrag(0); },
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/45 sm:items-center sm:p-4" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        ref={panel} role="dialog" aria-modal="true" aria-labelledby="reminder-sheet-title" tabIndex={-1} onKeyDown={onKey}
        style={drag ? { transform: `translateY(${drag}px)`, transition: 'none' } : undefined}
        className="flex max-h-[92dvh] w-full flex-col rounded-t-2xl bg-white shadow-xl outline-none sm:max-w-md sm:rounded-2xl"
      >
        <div data-sheet-handle role="separator" aria-label={t(language, 'remDragHandle')} {...handle}
          className="flex h-7 shrink-0 cursor-grab touch-none items-center justify-center sm:hidden">
          <span className="h-1.5 w-10 rounded-full bg-zinc-300" />
        </div>
        <div className="flex items-start justify-between gap-3 px-4 pb-2 pt-1 sm:pt-4">
          <div className="min-w-0">
            <h2 id="reminder-sheet-title" className="font-sans text-lg font-semibold leading-tight">{title}</h2>
            <p className="mt-0.5 break-words text-sm text-zinc-600">{subtitle}</p>
          </div>
          <button type="button" onClick={onClose} aria-label={t(language, 'remClose')}
            className="-mr-2 hidden h-11 w-11 shrink-0 items-center justify-center rounded-lg text-xl leading-none text-zinc-600 hover:bg-zinc-100 sm:inline-flex">
            <span aria-hidden>×</span>
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-3 pt-2">
          {none ? (
            <div className="flex flex-col items-start gap-3 py-2">
              <p className="text-sm text-zinc-700">{t(language, 'remSheetNoneHint')}</p>
              <button type="button" onClick={() => setNone(false)} className="inline-flex min-h-11 items-center text-sm text-zinc-800 underline underline-offset-2">{t(language, 'remSheetNoneUndo')}</button>
              {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
            </div>
          ) : (
            <>
              <ReminderFields language={language} value={choice} onChange={(v) => { setChoice(v); setError(''); }} note={note} onNote={setNote} now={now} idPrefix="sheet" error={error} disabled={busy} />
              <button type="button" onClick={() => setNone(true)} className="mt-2 inline-flex min-h-11 items-center text-sm text-zinc-600 underline underline-offset-2">{t(language, 'remSheetNone')}</button>
            </>
          )}
        </div>

        <div className="grid shrink-0 grid-cols-2 gap-2 border-t border-zinc-200 px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <button type="button" className={btnQuiet} onClick={onClose} disabled={busy}>{t(language, 'remCancel')}</button>
          <button type="button" className={btnPrimary} onClick={() => void submit()} disabled={busy} data-testid="reminder-sheet-save">{t(language, 'remSheetConfirm')}</button>
        </div>
      </div>
    </div>
  );
}

/** The Reached out popup: bottom sheet on phones (swipe down by the handle only), centered dialog from sm up. */
export default function ReminderSheet({ open, ...rest }: {
  open: boolean; title: string; subtitle: string; language: Language | null; onClose: () => void; onSubmit: (body: ReachedOutBody) => Promise<boolean>;
}) {
  if (!open || typeof document === 'undefined') return null;
  return createPortal(<Sheet {...rest} />, document.body);
}
