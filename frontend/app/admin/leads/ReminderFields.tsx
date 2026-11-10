'use client';

import { useState } from 'react';
import { tf, t } from '../../lib/i18n';
import type { Language } from '../../lib/types';
import {
  REMINDER_CHIPS, addDays, choiceDate, chipLabel, formatDay, formatTime, ptYmd, type ReminderChoice,
} from './reminders';

export const fieldCls = 'block w-full min-w-0 rounded-lg border border-zinc-300 bg-white px-3 text-base text-zinc-900 focus:border-zinc-600 focus:outline-none focus:ring-2 focus:ring-zinc-300 sm:text-sm';
export const btnPrimary = 'inline-flex min-h-11 items-center justify-center rounded-lg bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-800 disabled:opacity-40';
export const btnQuiet = 'inline-flex min-h-11 items-center justify-center rounded-lg px-4 text-sm font-medium text-zinc-800 ring-1 ring-zinc-300 hover:bg-zinc-100 disabled:opacity-40';

/** Quick day chips, a date picker and an optional time: the reminder picker shared by the Reached out sheet and the card. */
export default function ReminderFields({ language, value, onChange, note, onNote, now, idPrefix, error, disabled = false }: {
  language: Language | null; value: ReminderChoice; onChange: (v: ReminderChoice) => void;
  note: string; onNote: (v: string) => void; now: number; idPrefix: string; error?: string; disabled?: boolean;
}) {
  const [timeOpen, setTimeOpen] = useState(value.timeSet);
  const date = choiceDate(value, now);
  const today = ptYmd(now);
  const summary = date
    ? `${formatDay(date, language, date.slice(0, 4) !== today.slice(0, 4))}, ${formatTime(value.time, language)} PT`
    : '';
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div role="radiogroup" aria-label={t(language, 'remSheetNext')} className="flex flex-wrap gap-2">
        {REMINDER_CHIPS.map((n) => {
          const on = value.days === n;
          return (
            <button
              key={n} type="button" role="radio" aria-checked={on} data-chip-selected={on ? '' : undefined} disabled={disabled}
              onClick={() => onChange({ ...value, days: n, date: addDays(today, n) })}
              className={`min-h-11 min-w-[4.5rem] flex-1 rounded-lg px-3 text-sm font-medium ring-1 sm:flex-none ${on ? 'bg-zinc-900 text-white ring-zinc-900' : 'bg-white text-zinc-800 ring-zinc-300 hover:bg-zinc-100'}`}
            >
              {chipLabel(n, language)}
            </button>
          );
        })}
      </div>

      <div className="flex min-w-0 flex-wrap items-end gap-3">
        <div className="min-w-0 flex-1 basis-40">
          <label htmlFor={`${idPrefix}-date`} className="mb-1 block text-xs font-medium text-zinc-600">{t(language, 'remPickDate')}</label>
          <input
            id={`${idPrefix}-date`} type="date" min={today} value={date} disabled={disabled}
            onChange={(e) => e.target.value && onChange({ ...value, days: null, date: e.target.value })}
            className={`${fieldCls} min-h-11`}
          />
        </div>
        {timeOpen ? (
          <div className="min-w-0 flex-1 basis-32">
            <label htmlFor={`${idPrefix}-time`} className="mb-1 block text-xs font-medium text-zinc-600">{t(language, 'remTime')}</label>
            <input
              id={`${idPrefix}-time`} type="time" value={value.time} disabled={disabled}
              onChange={(e) => e.target.value && onChange({ ...value, time: e.target.value, timeSet: true })}
              className={`${fieldCls} min-h-11`}
            />
          </div>
        ) : (
          <button type="button" disabled={disabled} onClick={() => { setTimeOpen(true); onChange({ ...value, timeSet: true }); }}
            className="inline-flex min-h-11 items-center px-1 text-sm text-zinc-700 underline underline-offset-2">
            {t(language, 'remAddTime')}
          </button>
        )}
      </div>

      <div>
        <label htmlFor={`${idPrefix}-note`} className="mb-1 block text-xs font-medium text-zinc-600">{t(language, 'remNote')}</label>
        <textarea
          id={`${idPrefix}-note`} rows={2} maxLength={500} value={note} disabled={disabled}
          onChange={(e) => onNote(e.target.value)} placeholder={t(language, 'remNotePlaceholder')}
          className={`${fieldCls} resize-y py-2`}
        />
      </div>

      <p role="status" aria-live="polite" className="text-sm text-zinc-700">
        <span className="text-zinc-500">{t(language, 'remSheetSummary')}: </span>
        <span className="font-medium" data-testid="reminder-summary">{summary}</span>
      </p>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
    </div>
  );
}

export function inDaysLabel(n: number, overdue: boolean, language: Language | null): string {
  if (overdue) return n < 0 ? tf(language, 'remLate', { n: -n }) : t(language, 'remOverdue');
  return n <= 0 ? t(language, 'remToday') : n === 1 ? t(language, 'remTomorrow') : tf(language, 'remIn', { n });
}
