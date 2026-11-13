'use client';

import type { Language } from '../lib/types';
import { t } from '../lib/i18n';
import { type Answer, type BreakQuestion, hhmm, laToIso } from '../lib/timecards';

export type QState = { kind: 'MEAL' | 'REST'; index: number; answer: '' | 'YES' | 'NO' | 'DIFF'; s: string; e: string; preset: string; text: string };

const PRESETS = ['BUSY', 'NO_COVER', 'CLIENT_LATE', 'FORGOT', 'OTHER'] as const;

export function reasonLabel(lang: Language, reason: string | null): string {
  if (!reason) return '';
  if (reason.startsWith('preset:')) {
    const key = `tcBqRSN_${reason.slice(7)}` as 'tcBqRSN_OTHER';
    try {
      return t(lang, key);
    } catch {
      return reason.slice(7);
    }
  }
  return reason;
}

export function initStates(questions: BreakQuestion[], prior: Answer[]): QState[] {
  return questions.map((q) => {
    const a = prior.find((p) => p.kind === q.kind && p.index === q.index);
    const preset = a?.reason?.startsWith('preset:') ? a.reason.slice(7) : a?.reason ? 'OTHER' : '';
    return {
      kind: q.kind,
      index: q.index,
      answer: a ? a.answer : '',
      s: a?.start ? hhmm(a.start) : '',
      e: a?.end ? hhmm(a.end) : '',
      preset,
      text: a?.reason && !a.reason.startsWith('preset:') ? a.reason : '',
    };
  });
}

function reasonOf(s: QState): string | null {
  if (s.preset === 'OTHER') return s.text.trim() || null;
  return s.preset ? `preset:${s.preset}` : null;
}

function shortMeal(s: QState) {
  return s.kind === 'MEAL' && s.answer === 'DIFF' && s.s && s.e && s.e > s.s && minutes(s.s, s.e) < 30;
}

function minutes(a: string, b: string) {
  const [ah, am] = a.split(':').map(Number);
  const [bh, bm] = b.split(':').map(Number);
  return bh * 60 + bm - (ah * 60 + am);
}

export function complete(states: QState[]): boolean {
  return states.every((s) => {
    if (s.answer === 'YES') return true;
    if (s.answer === 'NO') return reasonOf(s) !== null;
    if (s.answer === 'DIFF') return !!s.s && !!s.e && s.e > s.s && (!shortMeal(s) || reasonOf(s) !== null);
    return false;
  });
}

export function toPayload(date: string, states: QState[]) {
  return states.map((s) => ({
    kind: s.kind,
    index: s.index,
    answer: s.answer,
    start: s.answer === 'DIFF' ? laToIso(date, s.s) : null,
    end: s.answer === 'DIFF' ? laToIso(date, s.e) : null,
    reason: s.answer === 'YES' ? null : reasonOf(s),
  }));
}

const field = 'min-h-[44px] w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-base focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 sm:text-sm';

export default function BreakQuestions({ lang, questions, states, onChange }: {
  lang: Language; questions: BreakQuestion[]; states: QState[]; onChange: (s: QState[]) => void;
}) {
  const set = (i: number, patch: Partial<QState>) => onChange(states.map((s, j) => (j === i ? { ...s, ...patch } : s)));
  const allAssumed = questions.length > 0 && questions.every((q) => q.assumedStart);
  const perKind = (k: string) => questions.filter((q) => q.kind === k).length;
  return (
    <fieldset className="flex min-w-0 flex-col gap-3 rounded-lg border border-zinc-200 bg-white p-3">
      <legend className="px-1 text-sm font-medium">{t(lang, 'tcBqTitle')}</legend>
      <p className="text-xs text-zinc-600">{t(lang, 'tcBqHint')}</p>
      {allAssumed && (
        <button
          type="button"
          onClick={() => onChange(states.map((s) => ({ ...s, answer: 'YES' as const })))}
          className="min-h-[44px] w-full rounded-md bg-zinc-900 px-4 py-2.5 text-sm font-medium text-white focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 focus-visible:ring-offset-2"
        >
          {t(lang, 'tcBqAllShown')}
        </button>
      )}
      {questions.map((q, i) => {
        const s = states[i];
        if (!s) return null;
        const label = `${t(lang, q.kind === 'MEAL' ? 'tcBqMeal' : 'tcBqRest')}${perKind(q.kind) > 1 ? ` ${q.index + 1}` : ''}`;
        const opts: [QState['answer'], string][] = q.assumedStart
          ? [['YES', t(lang, 'tcBqYes')], ['NO', t(lang, 'tcBqNo')], ['DIFF', t(lang, 'tcBqDiff')]]
          : [['DIFF', t(lang, 'tcBqTook')], ['NO', t(lang, 'tcBqNo')]];
        const needReason = s.answer === 'NO' || shortMeal(s);
        return (
          <div key={`${q.kind}-${q.index}`} role="group" aria-label={label} className="flex min-w-0 flex-col gap-2 border-t border-zinc-100 pt-3 first:border-t-0 first:pt-0">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <span className="text-sm font-medium">{label}</span>
              <span className="text-xs tabular-nums text-zinc-500">
                {q.assumedStart && q.assumedEnd ? `${t(lang, 'tcBqAssumed')}: ${hhmm(q.assumedStart)}-${hhmm(q.assumedEnd)}` : t(lang, 'tcBqNoneAssumed')}
              </span>
            </div>
            <div className={`grid gap-2 ${opts.length === 3 ? 'grid-cols-3' : 'grid-cols-2'}`}>
              {opts.map(([v, text]) => (
                <button
                  key={v}
                  type="button"
                  aria-pressed={s.answer === v}
                  onClick={() => set(i, { answer: v })}
                  className={`min-h-[44px] rounded-md border px-2 py-2 text-sm leading-tight focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 ${s.answer === v ? 'border-zinc-900 bg-zinc-900 text-white' : 'border-zinc-300 bg-white text-zinc-800'}`}
                >
                  {text}
                </button>
              ))}
            </div>
            {s.answer === 'DIFF' && (
              <div className="grid grid-cols-2 gap-2">
                <label className="flex min-w-0 flex-col gap-1 text-xs text-zinc-700">
                  {t(lang, 'tcBqFrom')}
                  <input type="time" value={s.s} onChange={(ev) => set(i, { s: ev.target.value })} className={field} />
                </label>
                <label className="flex min-w-0 flex-col gap-1 text-xs text-zinc-700">
                  {t(lang, 'tcBqTo')}
                  <input type="time" value={s.e} onChange={(ev) => set(i, { e: ev.target.value })} className={field} />
                </label>
              </div>
            )}
            {needReason && (
              <div className="flex min-w-0 flex-col gap-2">
                {shortMeal(s) && <div className="text-xs text-amber-800">{t(lang, 'tcBqShortMeal')}</div>}
                <label className="flex min-w-0 flex-col gap-1 text-xs text-zinc-700">
                  {t(lang, 'tcBqReason')}
                  <select value={s.preset} onChange={(ev) => set(i, { preset: ev.target.value })} className={field}>
                    <option value="" disabled>{t(lang, 'tcBqReason')}</option>
                    {PRESETS.map((p) => (
                      <option key={p} value={p}>{t(lang, `tcBqRSN_${p}` as 'tcBqRSN_OTHER')}</option>
                    ))}
                  </select>
                </label>
                {s.preset === 'OTHER' && (
                  <input maxLength={200} aria-label={t(lang, 'tcBqReasonText')} placeholder={t(lang, 'tcBqReasonText')} value={s.text} onChange={(ev) => set(i, { text: ev.target.value })} className={field} />
                )}
              </div>
            )}
          </div>
        );
      })}
    </fieldset>
  );
}
