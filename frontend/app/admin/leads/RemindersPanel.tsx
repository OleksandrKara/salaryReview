'use client';

import { useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, LeadReminder } from '../../lib/types';
import ReminderFields, { btnPrimary, btnQuiet, inDaysLabel } from './ReminderFields';
import {
  DEFAULT_DAYS, DEFAULT_TIME, addDays, choiceBody, choiceInPast, dueParts, dueRelative, formatDue, ptYmd, type ReminderChoice,
} from './reminders';

const ORIGINS = ['REACHED_OUT', 'ATTEMPT', 'MANUAL', 'AUTO', 'LAPSED', 'LEGACY'];

function Editor({ language, now, initial, initialNote, saveLabel, onSave, onCancel, idPrefix }: {
  language: Language | null; now: number; initial: ReminderChoice; initialNote: string; saveLabel: string;
  onSave: (c: ReminderChoice, note: string) => Promise<boolean>; onCancel: () => void; idPrefix: string;
}) {
  const [choice, setChoice] = useState(initial);
  const [note, setNote] = useState(initialNote);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function save() {
    if (choiceInPast(choice, now)) { setError(t(language, 'remPast')); return; }
    setBusy(true);
    const ok = await onSave(choice, note);
    setBusy(false);
    if (!ok) setError(t(language, 'remErr'));
  }
  return (
    <form
      className="flex min-w-0 flex-col gap-3 rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-200"
      onSubmit={(e) => { e.preventDefault(); void save(); }}
      onKeyDown={(e) => { if (e.key === 'Escape') onCancel(); }}
    >
      <ReminderFields language={language} value={choice} onChange={(v) => { setChoice(v); setError(''); }} note={note} onNote={setNote} now={now} idPrefix={idPrefix} error={error} disabled={busy} />
      <div className="grid grid-cols-2 gap-2 sm:flex sm:justify-end">
        <button type="button" className={btnQuiet} onClick={onCancel} disabled={busy}>{t(language, 'remCancel')}</button>
        <button type="submit" className={btnPrimary} disabled={busy}>{saveLabel}</button>
      </div>
    </form>
  );
}

/** Every open reminder of a lead, each one editable and removable, plus a form to add another. */
export default function RemindersPanel({ leadId, reminders, language, now, onChanged }: {
  leadId: number; reminders: LeadReminder[]; language: Language | null; now: number; onChanged: () => Promise<void>;
}) {
  const [editing, setEditing] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [error, setError] = useState('');
  const base = `/api/leads/${leadId}/reminders`;

  async function call(url: string, method: string, body?: unknown): Promise<boolean> {
    setError('');
    const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!res.ok) { setError(t(language, 'remErr')); return false; }
    await onChanged();
    return true;
  }

  const newChoice: ReminderChoice = { days: DEFAULT_DAYS, date: addDays(ptYmd(now), DEFAULT_DAYS), time: DEFAULT_TIME, timeSet: false };

  return (
    <section className="flex min-w-0 flex-col gap-3 rounded-lg p-4 ring-1 ring-zinc-200" data-testid="lead-reminders" aria-labelledby="lead-reminders-title">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="lead-reminders-title" className="font-sans text-sm font-semibold">{t(language, 'remTitle')}</h2>
        {!adding && (
          <button type="button" className={btnQuiet} onClick={() => { setAdding(true); setEditing(null); }}>+ {t(language, 'remAdd')}</button>
        )}
      </div>

      {reminders.length === 0 && !adding && <p className="text-sm text-zinc-600">{t(language, 'remEmpty')}</p>}

      {reminders.length > 0 && (
        <ul className="flex min-w-0 flex-col divide-y divide-zinc-200">
          {reminders.map((r) => {
            const rel = dueRelative(r.dueAt, now);
            const parts = dueParts(r.dueAt);
            return (
              <li key={r.id} className="min-w-0 py-3 first:pt-0 last:pb-0">
                {editing === r.id ? (
                  <Editor
                    language={language} now={now} idPrefix={`rem-${r.id}`} saveLabel={t(language, 'remSave')}
                    initial={{ days: null, date: parts.date, time: parts.time, timeSet: true }} initialNote={r.note ?? ''}
                    onCancel={() => setEditing(null)}
                    onSave={async (c, note) => {
                      const ok = await call(`${base}/${r.id}`, 'PATCH', { date: c.days != null ? addDays(ptYmd(now), c.days) : c.date, time: c.time, note: note.trim() });
                      if (ok) setEditing(null);
                      return ok;
                    }}
                  />
                ) : (
                  <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <span className="text-sm font-medium tabular-nums">{formatDue(r.dueAt, language, now)} <span className="text-xs font-normal text-zinc-500">PT</span></span>
                        <span className={`rounded px-1.5 py-0.5 text-xs ${rel.overdue ? 'bg-red-100 text-red-800' : 'bg-amber-100 text-amber-800'}`}>{inDaysLabel(rel.n, rel.overdue, language)}</span>
                      </div>
                      <div className="mt-0.5 text-xs text-zinc-500">
                        {t(language, `remOrigin_${ORIGINS.includes(r.origin) ? r.origin : 'MANUAL'}` as 'remOrigin_MANUAL')}
                        {r.createdBy && r.createdBy !== 'System' ? ` · ${r.createdBy}` : ''}
                        {r.fired ? ` · ${t(language, 'remSent')}` : ''}
                      </div>
                      {r.note && <p className="mt-1 whitespace-pre-wrap break-words text-sm text-zinc-800">{r.note}</p>}
                    </div>
                    {deleting === r.id ? (
                      <div className="flex min-w-0 flex-col gap-2 sm:items-end" role="group" aria-label={t(language, 'remDeleteAsk')}>
                        <span className="text-sm text-red-800">{t(language, 'remDeleteAsk')}</span>
                        <div className="grid grid-cols-2 gap-2">
                          <button type="button" className={btnQuiet} onClick={() => setDeleting(null)}>{t(language, 'remDeleteNo')}</button>
                          <button type="button" className="inline-flex min-h-11 items-center justify-center rounded-lg bg-red-600 px-4 text-sm font-medium text-white"
                            onClick={async () => { if (await call(`${base}/${r.id}`, 'DELETE')) setDeleting(null); }}>{t(language, 'remDeleteYes')}</button>
                        </div>
                      </div>
                    ) : (
                      <div className="grid shrink-0 grid-cols-2 gap-2">
                        <button type="button" className={btnQuiet} onClick={() => { setEditing(r.id); setAdding(false); setDeleting(null); }}>{t(language, 'remEdit')}</button>
                        <button type="button" className={btnQuiet} onClick={() => setDeleting(r.id)}>{t(language, 'remDelete')}</button>
                      </div>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {adding && (
        <Editor
          language={language} now={now} idPrefix="rem-new" saveLabel={t(language, 'remAdd')} initial={newChoice} initialNote=""
          onCancel={() => setAdding(false)}
          onSave={async (c, note) => {
            const ok = await call(base, 'POST', choiceBody(c, note));
            if (ok) setAdding(false);
            return ok;
          }}
        />
      )}
      {error && !adding && editing == null && <p role="alert" className="text-sm text-red-700">{error}</p>}
    </section>
  );
}
