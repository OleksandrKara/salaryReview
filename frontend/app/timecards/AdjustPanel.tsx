'use client';

import { useState } from 'react';
import type { Language } from '../lib/types';
import { t } from '../lib/i18n';
import { hhmm, laToIso } from '../lib/timecards';

export default function AdjustPanel({ lang, date, start, end, fromSquare, hasMeal, busy, err, onConfirm, onClose }: {
  lang: Language; date: string; start: string; end: string; fromSquare: boolean; hasMeal: boolean; busy: boolean; err: string;
  onConfirm: (startIso: string, endIso: string, note: string, removeMeal: boolean) => void; onClose: () => void;
}) {
  const [s, setS] = useState(hhmm(start));
  const [e, setE] = useState(hhmm(end));
  const [note, setNote] = useState('');
  const [noMeal, setNoMeal] = useState(false);
  const endIso = e && s && e <= s ? laToIso(nextDay(date), e) : e ? laToIso(date, e) : '';
  const startIso = s ? laToIso(date, s) : '';
  const inputCls = 'w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base sm:text-sm';
  return (
    <div role="dialog" aria-label={t(lang, 'tcAdjustTitle')} className="mt-3 flex flex-col gap-3 rounded-lg border border-zinc-300 bg-zinc-50 p-3 text-sm">
      <div className="font-medium">{t(lang, 'tcAdjustTitle')}</div>
      <p className="text-xs text-zinc-600">{t(lang, fromSquare ? 'tcAdjustHintSquare' : 'tcAdjustHintDraft')}</p>
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1 text-xs text-zinc-700">
          {t(lang, 'tcAdjustStart')}
          <input type="time" value={s} onChange={(ev) => setS(ev.target.value)} className={inputCls} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-zinc-700">
          {t(lang, 'tcAdjustEnd')}
          <input type="time" value={e} onChange={(ev) => setE(ev.target.value)} className={inputCls} />
        </label>
      </div>
      {hasMeal && (
        <label className="flex items-start gap-2 text-sm text-zinc-800">
          <input type="checkbox" checked={noMeal} onChange={(ev) => setNoMeal(ev.target.checked)} className="mt-1" />
          {t(lang, 'tcAdjustNoMeal')}
        </label>
      )}
      <label className="flex flex-col gap-1 text-xs text-zinc-700">
        {t(lang, 'tcAdjustReason')}
        <input maxLength={300} value={note} onChange={(ev) => setNote(ev.target.value)} placeholder={t(lang, 'tcAdjustReasonPh')} className={inputCls} />
      </label>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <button
          type="button"
          disabled={busy || !startIso || !endIso || !note.trim()}
          onClick={() => onConfirm(startIso, endIso, note.trim(), noMeal)}
          className="w-full rounded-md bg-zinc-900 px-4 py-2.5 text-sm font-medium text-white disabled:opacity-50 sm:w-auto"
        >
          {t(lang, 'tcAdjustSave')}
        </button>
        <button type="button" onClick={onClose} className="w-full rounded-md border border-zinc-300 bg-white px-4 py-2.5 text-sm sm:w-auto">
          {t(lang, 'tcKeep')}
        </button>
      </div>
      {err && <div className="text-sm text-red-700">{err}</div>}
    </div>
  );
}

function nextDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
}
