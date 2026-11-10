'use client';

import { useState } from 'react';
import type { Language } from '../lib/types';
import { t } from '../lib/i18n';

export default function VoidPanel({ lang, hint, busy, err, onConfirm, onClose }: {
  lang: Language; hint: string; busy: boolean; err: string; onConfirm: (reason: string) => void; onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  return (
    <div role="alertdialog" aria-label={t(lang, 'tcVoidTitle')} className="mt-3 flex flex-col gap-3 rounded-lg border border-red-200 bg-red-50/60 p-3 text-sm">
      <div className="font-medium text-red-900">{t(lang, 'tcVoidTitle')}</div>
      <p className="text-xs text-zinc-600">{hint}</p>
      <label className="flex flex-col gap-1 text-xs text-zinc-700">
        {t(lang, 'tcVoidReason')}
        <input
          autoFocus
          maxLength={300}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder={t(lang, 'tcVoidReasonPh')}
          className="w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base sm:text-sm"
        />
      </label>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <button
          type="button"
          disabled={busy || !reason.trim()}
          onClick={() => onConfirm(reason.trim())}
          className="w-full rounded-md bg-red-700 px-4 py-2.5 text-sm font-medium text-white disabled:opacity-50 sm:w-auto"
        >
          {t(lang, 'tcVoidConfirm')}
        </button>
        <button type="button" onClick={onClose} className="w-full rounded-md border border-zinc-300 bg-white px-4 py-2.5 text-sm sm:w-auto">
          {t(lang, 'tcKeep')}
        </button>
      </div>
      {err && <div className="text-sm text-red-700">{err}</div>}
    </div>
  );
}
