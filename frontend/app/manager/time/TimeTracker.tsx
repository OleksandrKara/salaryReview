'use client';

import { useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { Language, ManagerTimesheet, TimeEntry, TimeEntryInput } from '../../lib/types';

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

/** "6h 45m", "45m", or "0m". */
function fmtHM(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** Live elapsed "H:MM:SS" from an ISO start to now. */
function elapsed(startIso: string, nowMs: number) {
  const s = Math.max(0, Math.floor((nowMs - new Date(startIso).getTime()) / 1000));
  const hh = Math.floor(s / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  return `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
}

function todayLocal() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

type Draft = { date: string; startTime: string; endTime: string; note: string };
const emptyDraft = (): Draft => ({ date: todayLocal(), startTime: '', endTime: '', note: '' });

export default function TimeTracker({
  initial,
  language,
}: {
  initial: ManagerTimesheet;
  language: Language | null;
}) {
  const [entries, setEntries] = useState<TimeEntry[]>(initial.entries);
  const [open, setOpen] = useState<TimeEntry | null>(initial.open);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [showAdd, setShowAdd] = useState(false);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [editId, setEditId] = useState<number | null>(null);
  const [editDraft, setEditDraft] = useState<Draft>(emptyDraft);

  const rate = initial.usdPerHour; // read-only; only the owner sets it
  const monthPrefix = `${initial.year}-${String(initial.month).padStart(2, '0')}`;
  const inThisMonth = (e: TimeEntry) => e.workDate.startsWith(monthPrefix);

  // Tick the live timer once a second while clocked in.
  useEffect(() => {
    if (!open) return;
    const id = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, [open]);

  const totals = useMemo(() => {
    const month = entries.reduce((sum, e) => sum + e.minutes, 0);
    return { month, monthPay: rate == null ? null : (rate * month) / 60 };
  }, [entries, rate]);

  function upsertEntry(e: TimeEntry) {
    setEntries((prev) => {
      const without = prev.filter((x) => x.id !== e.id);
      if (!inThisMonth(e)) return without; // moved out of the viewed month
      return [...without, e].sort((a, b) => a.startAt.localeCompare(b.startAt));
    });
  }

  async function run(fn: () => Promise<void>) {
    setError('');
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message.replace(/^\d+\s*/, '') : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  }

  const clockIn = () => run(async () => setOpen(await api.clockIn()));
  const clockOut = () =>
    run(async () => {
      const done = await api.clockOut();
      setOpen(null);
      upsertEntry(done);
    });

  const addShift = () =>
    run(async () => {
      const body: TimeEntryInput = { date: draft.date, startTime: draft.startTime, endTime: draft.endTime, note: draft.note || null };
      upsertEntry(await api.addTimeEntry(body));
      setDraft(emptyDraft());
      setShowAdd(false);
    });

  const saveEdit = (id: number) =>
    run(async () => {
      const body: TimeEntryInput = { date: editDraft.date, startTime: editDraft.startTime, endTime: editDraft.endTime, note: editDraft.note || null };
      upsertEntry(await api.updateTimeEntry(id, body));
      setEditId(null);
    });

  const del = (id: number) => run(async () => {
    await api.deleteTimeEntry(id);
    setEntries((prev) => prev.filter((x) => x.id !== id));
  });

  function beginEdit(e: TimeEntry) {
    setEditId(e.id);
    // Prefill from the raw instants in the salon-local wall-clock the labels came from.
    const [sh, sm] = to24(e.startLabel);
    const [eh, em] = to24(e.endLabel ?? '');
    setEditDraft({ date: e.workDate, startTime: sh ? `${sh}:${sm}` : '', endTime: eh ? `${eh}:${em}` : '', note: e.note ?? '' });
  }

  return (
    <div className="space-y-5">
      {error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 ring-1 ring-red-200">{error}</p>}

      {/* Period summary */}
      <section className="rounded-xl p-4 ring-1 ring-zinc-200">
        <div className="flex items-end justify-between">
          <div>
            <div className="text-xs uppercase tracking-wide text-zinc-500">{t(language, 'timeMonthTotal')}</div>
            <div className="mt-0.5 text-2xl font-semibold tabular-nums">{fmtHM(totals.month)}</div>
          </div>
          {rate != null && (
            <div className="text-right">
              <div className="text-xs text-zinc-500">${rate.toFixed(2)}/hr</div>
              <div className="text-2xl font-semibold tabular-nums text-emerald-700">{usd(totals.monthPay ?? 0)}</div>
            </div>
          )}
        </div>
        {rate == null && <p className="mt-3 text-xs text-amber-700">{t(language, 'timeRateUnset')}</p>}
      </section>

      {/* Clock in / out */}
      <section className="rounded-xl p-4 text-center ring-1 ring-zinc-200">
        {open ? (
          <>
            <div className="text-xs text-zinc-500">
              {t(language, 'timeClockedInSince')} <span className="font-medium text-zinc-700">{open.startLabel}</span>
            </div>
            <div className="my-2 font-mono text-4xl font-semibold tabular-nums text-emerald-700" data-testid="time-elapsed">
              {elapsed(open.startAt, nowMs)}
            </div>
            <button
              onClick={clockOut}
              disabled={busy}
              data-testid="clock-out"
              className="w-full rounded-lg bg-rose-600 px-4 py-3 text-base font-semibold text-white hover:bg-rose-700 disabled:opacity-50 sm:w-auto sm:px-10"
            >
              {t(language, 'timeClockOut')}
            </button>
          </>
        ) : (
          <button
            onClick={clockIn}
            disabled={busy}
            data-testid="clock-in"
            className="w-full rounded-lg bg-emerald-600 px-4 py-4 text-base font-semibold text-white hover:bg-emerald-700 disabled:opacity-50 sm:w-auto sm:px-14"
          >
            {t(language, 'timeClockIn')}
          </button>
        )}
      </section>

      {/* Manual add */}
      <section>
        {!showAdd ? (
          <button
            onClick={() => { setShowAdd(true); setDraft(emptyDraft()); }}
            className="text-sm font-medium text-zinc-600 hover:text-zinc-900"
          >
            + {t(language, 'timeAddShift')}
          </button>
        ) : (
          <ShiftForm
            language={language}
            draft={draft}
            setDraft={setDraft}
            busy={busy}
            onSubmit={addShift}
            onCancel={() => setShowAdd(false)}
            submitLabel={t(language, 'timeAdd')}
          />
        )}
      </section>

      {/* Entries */}
      {entries.length === 0 ? (
        <p className="rounded-lg p-4 text-center text-sm text-zinc-400 ring-1 ring-zinc-200">{t(language, 'timeNoEntries')}</p>
      ) : (
        <section>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">{t(language, 'timeShifts')}</h2>
          <ul className="space-y-2">
            {entries.map((e) =>
              editId === e.id ? (
                <li key={e.id} className="rounded-lg p-3 ring-1 ring-zinc-200">
                  <ShiftForm
                    language={language}
                    draft={editDraft}
                    setDraft={setEditDraft}
                    busy={busy}
                    onSubmit={() => saveEdit(e.id)}
                    onCancel={() => setEditId(null)}
                    submitLabel={t(language, 'timeSave')}
                  />
                </li>
              ) : (
                <li
                  key={e.id}
                  data-testid={`time-row-${e.id}`}
                  className="flex items-center justify-between gap-3 rounded-lg px-3 py-2.5 text-sm ring-1 ring-zinc-200"
                >
                  <div className="min-w-0">
                    <div className="font-medium">{weekday(e.workDate)}</div>
                    <div className="text-zinc-500">
                      {e.startLabel} – {e.endLabel}
                      {e.note && <span className="text-zinc-400"> · {e.note}</span>}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <span className="tabular-nums font-medium">{fmtHM(e.minutes)}</span>
                    <button onClick={() => beginEdit(e)} className="text-xs text-zinc-500 hover:text-zinc-900">
                      {t(language, 'timeEdit')}
                    </button>
                    <button
                      onClick={() => del(e.id)}
                      disabled={busy}
                      data-testid={`time-delete-${e.id}`}
                      className="text-xs text-rose-600 hover:text-rose-800 disabled:opacity-50"
                    >
                      {t(language, 'timeDelete')}
                    </button>
                  </div>
                </li>
              ),
            )}
          </ul>
        </section>
      )}
    </div>
  );
}

/** Shared date + start/end + note form for adding and editing a shift. */
function ShiftForm({
  language,
  draft,
  setDraft,
  busy,
  onSubmit,
  onCancel,
  submitLabel,
}: {
  language: Language | null;
  draft: Draft;
  setDraft: (d: Draft) => void;
  busy: boolean;
  onSubmit: () => void;
  onCancel: () => void;
  submitLabel: string;
}) {
  const label = 'block text-xs text-zinc-500';
  const input = 'mt-0.5 w-full rounded-md border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none';
  const valid = draft.date && draft.startTime && draft.endTime;
  return (
    <div className="rounded-lg bg-zinc-50 p-3 ring-1 ring-zinc-200">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <label className="col-span-2 sm:col-span-1">
          <span className={label}>{t(language, 'timeDate')}</span>
          <input type="date" value={draft.date} onChange={(e) => setDraft({ ...draft, date: e.target.value })} className={input} />
        </label>
        <label>
          <span className={label}>{t(language, 'timeStart')}</span>
          <input type="time" value={draft.startTime} onChange={(e) => setDraft({ ...draft, startTime: e.target.value })} className={input} />
        </label>
        <label>
          <span className={label}>{t(language, 'timeEnd')}</span>
          <input type="time" value={draft.endTime} onChange={(e) => setDraft({ ...draft, endTime: e.target.value })} className={input} />
        </label>
        <label className="col-span-2 sm:col-span-4">
          <span className={label}>{t(language, 'timeNote')}</span>
          <input type="text" value={draft.note} maxLength={255} onChange={(e) => setDraft({ ...draft, note: e.target.value })} className={input} />
        </label>
      </div>
      <div className="mt-3 flex items-center gap-2">
        <button
          onClick={onSubmit}
          disabled={busy || !valid}
          className="rounded-md bg-zinc-900 px-4 py-1.5 text-sm font-medium text-white hover:bg-zinc-800 disabled:opacity-50"
        >
          {submitLabel}
        </button>
        <button onClick={onCancel} className="text-sm text-zinc-500 hover:text-zinc-800">
          {t(language, 'timeCancel')}
        </button>
      </div>
    </div>
  );
}

/** "2026-07-05" → "Sun, Jul 5". */
function weekday(iso: string) {
  const d = new Date(`${iso}T00:00:00`);
  return d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
}

/** "9:00 AM" → ["09","00"]; "" → ["",""]. For prefilling the edit form's time inputs. */
function to24(label: string): [string, string] {
  const m = label.match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i);
  if (!m) return ['', ''];
  let h = parseInt(m[1], 10);
  const min = m[2];
  const pm = m[3].toUpperCase() === 'PM';
  if (pm && h !== 12) h += 12;
  if (!pm && h === 12) h = 0;
  return [String(h).padStart(2, '0'), min];
}
