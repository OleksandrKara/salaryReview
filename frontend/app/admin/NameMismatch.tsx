'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../lib/i18n';
import type { Language } from '../lib/types';
import { CONFIRM_ARM_MS, confirmArmed } from './messages/confirmSend';

const CLOSE_DISTANCE = 90;
const FOCUSABLE = 'button:not([disabled]), [tabindex="0"]';

function NameDialog({ language, from, to, saving, error, onConfirm, onCancel }: {
  language: Language | null;
  from: string | null;
  to: string;
  saving: boolean;
  error: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const cancelBtn = useRef<HTMLButtonElement>(null);
  const openedAt = useRef(0);
  const startY = useRef<number | null>(null);
  const [drag, setDrag] = useState(0);

  useEffect(() => {
    openedAt.current = Date.now();
    const before = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    cancelBtn.current?.focus();
    return () => { document.body.style.overflow = overflow; before?.focus?.(); };
  }, []);

  function cancel() { if (!saving) onCancel(); }
  function confirm() { if (saving || !confirmArmed(openedAt.current, Date.now())) return; onConfirm(); }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === 'Escape') { e.stopPropagation(); cancel(); return; }
    if (e.key === 'Enter' && (e.target as HTMLElement).tagName !== 'BUTTON') { e.preventDefault(); return; }
    if (e.key === 'Tab' && panel.current) {
      const f = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((x) => x.offsetParent !== null);
      if (f.length === 0) return;
      const a = document.activeElement;
      if (e.shiftKey && (a === f[0] || a === panel.current)) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && a === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
    }
  }

  const handle = {
    onPointerDown: (e: React.PointerEvent) => { startY.current = e.clientY; e.currentTarget.setPointerCapture(e.pointerId); },
    onPointerMove: (e: React.PointerEvent) => { if (startY.current != null) setDrag(Math.max(0, e.clientY - startY.current)); },
    onPointerUp: () => { const d = drag; startY.current = null; setDrag(0); if (d > CLOSE_DISTANCE) cancel(); },
    onPointerCancel: () => { startY.current = null; setDrag(0); },
  };

  return createPortal(
    <div data-testid="name-confirm-backdrop" className="fixed inset-0 z-[60] flex items-end justify-center bg-black/50 sm:items-center sm:p-4"
      onMouseDown={(e) => { if (e.target === e.currentTarget) cancel(); }}>
      <div
        ref={panel} role="alertdialog" aria-modal="true" aria-labelledby="name-confirm-title" aria-describedby="name-confirm-body" tabIndex={-1} onKeyDown={onKey}
        data-testid="name-confirm-dialog" data-arm-ms={CONFIRM_ARM_MS}
        style={drag ? { transform: `translateY(${drag}px)`, transition: 'none' } : undefined}
        className="flex max-h-[92dvh] w-full flex-col rounded-t-2xl bg-white shadow-xl outline-none sm:max-w-md sm:rounded-2xl"
      >
        <div data-sheet-handle role="separator" aria-label={t(language, 'remDragHandle')} {...handle}
          className="flex h-7 shrink-0 cursor-grab touch-none items-center justify-center sm:hidden">
          <span className="h-1.5 w-10 rounded-full bg-zinc-300" />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-3 pt-1 sm:pt-5">
          <h2 id="name-confirm-title" className="font-sans text-lg font-semibold leading-tight">{t(language, 'nameConfirmTitle')}</h2>
          <p className="mt-2 break-words text-sm text-zinc-600">
            {from && <span className="line-through">{from}</span>}
            {from && <span aria-hidden> → </span>}
            <span data-testid="name-confirm-new" className="font-semibold text-zinc-900">{to}</span>
          </p>
          <p id="name-confirm-body" className="mt-2 text-sm text-zinc-600">{t(language, 'nameConfirmBody')}</p>
          {error && <p role="alert" className="mt-2 text-sm text-red-700">{t(language, 'nameSaveFailed')}</p>}
        </div>
        <div className="flex shrink-0 flex-col-reverse gap-2 border-t border-zinc-200 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-end sm:pb-4">
          <button ref={cancelBtn} type="button" data-testid="name-confirm-cancel" onClick={cancel} disabled={saving}
            className="min-h-11 rounded-lg px-4 text-sm font-medium text-zinc-800 ring-1 ring-zinc-300 hover:bg-zinc-100 disabled:opacity-50 sm:min-w-28">
            {t(language, 'confirmSendCancel')}
          </button>
          <button type="button" data-testid="name-confirm-confirm" onClick={confirm} disabled={saving}
            className="min-h-11 rounded-lg bg-sky-600 px-4 text-sm font-semibold text-white hover:bg-sky-700 disabled:opacity-60 sm:min-w-28">
            {saving ? t(language, 'nameSaving') : t(language, 'confirmSendConfirm')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

/** Small note shown only when other real names exist for the same phone; one tap picks the right one (after confirmation). */
export default function NameMismatch({ language, currentName, alternates, canRename, onRename }: {
  language: Language | null;
  currentName: string | null;
  alternates: string[];
  canRename: boolean;
  onRename: (name: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [pick, setPick] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);

  if (alternates.length === 0) return null;

  async function confirm() {
    if (!pick) return;
    setSaving(true);
    setFailed(false);
    try {
      await onRename(pick);
      setPick(null);
      setOpen(false);
    } catch {
      setFailed(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div data-testid="name-mismatch" className="mt-1.5 min-w-0 max-w-full">
      <button
        type="button"
        data-testid="name-mismatch-toggle"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex min-h-8 max-w-full items-center gap-1 rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-medium text-amber-800 ring-1 ring-amber-200 hover:bg-amber-100"
      >
        <span aria-hidden>≠</span>
        <span className="truncate">{t(language, alternates.length > 1 ? 'nameOtherOnFileN' : 'nameOtherOnFile')}</span>
        <span aria-hidden className={`transition-transform ${open ? 'rotate-180' : ''}`}>▾</span>
      </button>
      {open && (
        <ul data-testid="name-mismatch-list" className="mt-1.5 flex flex-col gap-1.5">
          {alternates.map((n) => (
            <li key={n} className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg bg-zinc-50 px-3 py-2 ring-1 ring-zinc-200">
              <span className="min-w-0 break-words text-sm font-medium text-zinc-900">{n}</span>
              {canRename && (
                <button type="button" data-testid="name-use" onClick={() => { setFailed(false); setPick(n); }}
                  className="min-h-11 shrink-0 rounded-lg px-3 text-sm font-medium text-sky-700 ring-1 ring-sky-300 hover:bg-sky-50">
                  {t(language, 'nameUse')}
                </button>
              )}
            </li>
          ))}
          {!canRename && <li className="text-xs text-zinc-500">{t(language, 'nameNoSquare')}</li>}
        </ul>
      )}
      {pick && (
        <NameDialog language={language} from={currentName} to={pick} saving={saving} error={failed}
          onConfirm={confirm} onCancel={() => setPick(null)} />
      )}
    </div>
  );
}
