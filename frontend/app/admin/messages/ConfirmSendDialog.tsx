'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { t } from '../../lib/i18n';
import type { Language } from '../../lib/types';
import { CONFIRM_ARM_MS, confirmArmed, maskedPhone } from './confirmSend';

const CLOSE_DISTANCE = 90;
const FOCUSABLE = 'button:not([disabled]), [tabindex="0"]';

export default function ConfirmSendDialog({ language, recipientName, phone, channel, body, photoCount, sending, onConfirm, onCancel }: {
  language: Language | null;
  recipientName: string | null;
  phone: string;
  channel: 'SMS' | 'MMS';
  body: string;
  photoCount: number;
  sending: boolean;
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

  function cancel() { if (!sending) onCancel(); }

  function confirm() {
    if (sending || !confirmArmed(openedAt.current, Date.now())) return;
    onConfirm();
  }

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

  const masked = maskedPhone(phone);

  return createPortal(
    <div data-testid="confirm-send-backdrop" className="fixed inset-0 z-[60] flex items-end justify-center bg-black/50 sm:items-center sm:p-4"
      onMouseDown={(e) => { if (e.target === e.currentTarget) cancel(); }}>
      <div
        ref={panel} role="alertdialog" aria-modal="true" aria-labelledby="confirm-send-title" aria-describedby="confirm-send-body" tabIndex={-1} onKeyDown={onKey}
        data-testid="confirm-send-dialog" data-arm-ms={CONFIRM_ARM_MS}
        style={drag ? { transform: `translateY(${drag}px)`, transition: 'none' } : undefined}
        className="flex max-h-[92dvh] w-full flex-col rounded-t-2xl bg-white shadow-xl outline-none sm:max-w-md sm:rounded-2xl"
      >
        <div data-sheet-handle role="separator" aria-label={t(language, 'remDragHandle')} {...handle}
          className="flex h-7 shrink-0 cursor-grab touch-none items-center justify-center sm:hidden">
          <span className="h-1.5 w-10 rounded-full bg-zinc-300" />
        </div>
        <div className="px-4 pb-2 pt-1 sm:pt-5">
          <h2 id="confirm-send-title" className="font-sans text-lg font-semibold leading-tight">{t(language, 'confirmSendTitle')}</h2>
          <p className="mt-1 break-words text-sm text-zinc-600">
            <span className="font-medium text-zinc-900">{recipientName ?? t(language, 'confirmSendNoName')}</span>
            {masked && <span> · {masked}</span>}
            <span className="ml-2 rounded bg-zinc-100 px-1.5 py-0.5 text-xs font-medium text-zinc-700">{channel}</span>
          </p>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-3">
          <p id="confirm-send-body" data-testid="confirm-send-preview"
            className="whitespace-pre-wrap break-words rounded-xl bg-sky-50 px-3 py-2 text-base text-zinc-900 ring-1 ring-sky-100 sm:text-sm">
            {body || t(language, 'confirmSendPhotoOnly')}
          </p>
          {photoCount > 0 && (
            <p className="mt-2 text-sm text-zinc-600">{t(language, 'confirmSendPhotos')}: {photoCount}</p>
          )}
        </div>
        <div className="flex shrink-0 flex-col-reverse gap-2 border-t border-zinc-200 px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-end sm:pb-4">
          <button ref={cancelBtn} type="button" data-testid="confirm-send-cancel" onClick={cancel} disabled={sending}
            className="min-h-11 rounded-lg px-4 text-sm font-medium text-zinc-800 ring-1 ring-zinc-300 hover:bg-zinc-100 disabled:opacity-50 sm:min-w-28">
            {t(language, 'confirmSendCancel')}
          </button>
          <button type="button" data-testid="confirm-send-confirm" onClick={confirm} disabled={sending}
            className="min-h-11 rounded-lg bg-sky-600 px-4 text-sm font-semibold text-white hover:bg-sky-700 disabled:opacity-60 sm:min-w-28">
            {sending ? t(language, 'confirmSendSending') : t(language, 'confirmSendConfirm')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
