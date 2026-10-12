'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../../lib/api';
import type { AiCell, AiEngine, AiHistoryItem, AiPageComparison, AiPageGap, AiQueryRow, AiRanked, AiVisibilityOverview, AiVisibilitySettingsDto } from '../../../lib/types';
import { Spinner } from '../../../components/Spinner';

// openspec/changes/ai-visibility-monitoring design.md D7.
const ENGINES: { key: AiEngine; label: string; short: string }[] = [
  { key: 'CHATGPT', label: 'ChatGPT', short: 'ChatGPT' },
  { key: 'GOOGLE_AI_MODE', label: 'Google AI', short: 'Google' },
  { key: 'GEMINI', label: 'Gemini', short: 'Gemini' },
  { key: 'PERPLEXITY', label: 'Perplexity', short: 'Perplx' },
  { key: 'CLAUDE', label: 'Claude', short: 'Claude' },
];
const MAX_TRACKED = 20;

function fmtDate(iso: string | null, withTime = false): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', timeZone: 'America/Los_Angeles',
    ...(withTime ? { hour: 'numeric', minute: '2-digit' } : {}),
  });
}

function consistencyText(c: AiCell['recent']): string {
  if (c.total === 0) return '';
  return c.percent === null ? `${c.named}/${c.total}` : `${c.percent}% · ${c.named}/${c.total}`;
}

const CHIP_STYLE: Record<AiCell['latest'], string> = {
  NAMED: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  NOT_NAMED: 'bg-red-50 text-red-700 ring-red-200',
  NO_ANSWER: 'bg-zinc-100 text-zinc-500 ring-zinc-200',
  ERROR: 'bg-zinc-100 text-zinc-500 ring-zinc-200',
  NONE: 'bg-zinc-50 text-zinc-400 ring-zinc-200',
};

function chipLabel(cell: AiCell): string {
  switch (cell.latest) {
    case 'NAMED': return cell.rank ? `Named #${cell.rank}` : 'Named';
    case 'NOT_NAMED': return 'Not named';
    case 'NO_ANSWER': return 'No answer';
    case 'ERROR': return 'Error';
    default: return 'Not checked';
  }
}

function Chip({ cell, engineLabel }: { cell: AiCell; engineLabel?: string }) {
  const c = consistencyText(cell.recent);
  return (
    <div
      className={`inline-flex min-w-[5.5rem] flex-col items-start rounded-md px-2 py-1 ring-1 ring-inset ${CHIP_STYLE[cell.latest]}`}
      title={`Last check ${fmtDate(cell.checkedAt)}. Last 8 weeks: ${consistencyText(cell.recent) || 'no data'}. All time: ${consistencyText(cell.allTime) || 'no data'}.`}
    >
      {engineLabel && <span className="text-[10px] font-medium uppercase tracking-wide opacity-70">{engineLabel}</span>}
      <span className="text-xs font-semibold leading-tight">{chipLabel(cell)}</span>
      {c && <span className="text-[11px] leading-tight opacity-80">{c}</span>}
    </div>
  );
}

function lastChecked(row: AiQueryRow): string | null {
  const dates = Object.values(row.cells).map((c) => c.checkedAt).filter((d): d is string => !!d).sort();
  return dates.length ? dates[dates.length - 1] : null;
}

export default function AiVisibilityView({ initialData, canEdit }: { initialData: AiVisibilityOverview; canEdit: boolean }) {
  const [data, setData] = useState(initialData);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [historyFor, setHistoryFor] = useState<AiQueryRow | null>(null);
  const [runningRunId, setRunningRunId] = useState<number | null>(initialData.runningRunId);

  const refresh = useCallback(async () => {
    try {
      const fresh = await api.getAiVisibilityOverview();
      setData(fresh);
      setRunningRunId(fresh.runningRunId);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not refresh');
    }
  }, []);

  // A re-check runs in the background on the server; poll it until it finishes.
  useEffect(() => {
    if (!runningRunId) return;
    const t = setInterval(async () => {
      try {
        const run = await api.getAiRun(runningRunId);
        if (run.finishedAt) {
          setRunningRunId(null);
          await refresh();
        }
      } catch {
        setRunningRunId(null);
      }
    }, 5000);
    return () => clearInterval(t);
  }, [runningRunId, refresh]);

  async function act(key: string, fn: () => Promise<unknown>) {
    setBusy(key);
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
    } finally {
      setBusy(null);
    }
  }

  const proposed = data.rows.filter((r) => r.status === 'PROPOSED');
  const tracked = data.rows.filter((r) => r.status !== 'PROPOSED');
  const groups = useMemo(() => {
    const m = new Map<string, AiQueryRow[]>();
    for (const r of tracked) {
      const k = r.topic ?? 'other';
      m.set(k, [...(m.get(k) ?? []), r]);
    }
    return [...m.entries()];
  }, [tracked]);
  const countedNow = data.rows.filter((r) => r.status === 'PROPOSED' || r.status === 'ACTIVE').length;

  if (!data.configured) {
    return <p className="rounded-lg border border-zinc-200 bg-white p-4 text-sm text-zinc-600">AI visibility is not set up for this business yet.</p>;
  }

  return (
    <div className="space-y-6">
      {error && <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

      <section className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        {ENGINES.map((e) => {
          const t = data.engineTotals.find((x) => x.engine === e.key);
          const delta = t && t.previousNamed !== null ? t.named - t.previousNamed : null;
          return (
            <div key={e.key} className="rounded-lg border border-zinc-200 bg-white p-3">
              <div className="text-xs font-medium text-zinc-500">{e.label}</div>
              {t && t.answered > 0 ? (
                <div className="mt-1 flex items-baseline gap-2">
                  <span className="text-xl font-semibold text-zinc-900">{t.named}/{t.answered}</span>
                  {delta !== null && delta !== 0 && (
                    <span className={`text-xs font-medium ${delta > 0 ? 'text-emerald-700' : 'text-red-600'}`}>{delta > 0 ? `+${delta}` : delta}</span>
                  )}
                </div>
              ) : (
                <div className="mt-1 text-sm text-zinc-400">No data yet</div>
              )}
              <div className="text-[11px] text-zinc-400">named us in the last check</div>
            </div>
          );
        })}
      </section>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-zinc-500">
        <span>Last check: {data.lastRunAt ? fmtDate(data.lastRunAt) : 'not yet'}</span>
        <span>Next: {fmtDate(data.nextRunAt, true)} PT</span>
        <span>Spend this month: ${Number(data.monthSpend).toFixed(2)}{data.monthlyBudget !== null ? ` of $${Number(data.monthlyBudget).toFixed(0)}` : ''}</span>
        {runningRunId && <span className="inline-flex items-center gap-1 text-zinc-700"><Spinner className="h-3 w-3" /> Checking now…</span>}
      </div>

      {data.nextActions && (
        <section className="rounded-lg border border-sky-200 bg-sky-50 p-3 text-sm text-sky-900">
          <div className="text-xs font-semibold uppercase tracking-wide text-sky-700">In progress (Claude)</div>
          <p className="mt-1 whitespace-pre-line">{data.nextActions}</p>
        </section>
      )}

      {proposed.length > 0 && (
        <section className="rounded-lg border border-amber-200 bg-amber-50 p-3 sm:p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-amber-900">Suggested by Claude ({proposed.length})</h2>
            {canEdit && (
              <button type="button" disabled={busy !== null} onClick={() => act('approve-all', () => api.approveAllAiQueries())}
                className="rounded-md bg-amber-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
                {busy === 'approve-all' ? 'Approving…' : 'Approve all'}
              </button>
            )}
          </div>
          <p className="mt-1 text-xs text-amber-800">Approved questions are checked every Monday. Edit or remove any that don&rsquo;t fit.</p>
          <ul className="mt-3 divide-y divide-amber-200">
            {proposed.map((r) => (
              <ProposedItem key={r.id} row={r} canEdit={canEdit} disabled={busy !== null}
                onApprove={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: 'ACTIVE' }))}
                onRemove={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: 'REMOVED' }))}
                onSave={(text) => act(`q${r.id}`, () => api.updateAiQuery(r.id, { query: text }))} />
            ))}
          </ul>
        </section>
      )}

      {tracked.length === 0 && proposed.length === 0 && (
        <p className="rounded-lg border border-zinc-200 bg-white p-4 text-sm text-zinc-500">No questions tracked yet.</p>
      )}

      {groups.map(([topic, rows]) => (
        <section key={topic}>
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">{topic}</h2>
          {/* Desktop table */}
          <div className="hidden overflow-x-auto rounded-lg border border-zinc-200 bg-white md:block">
            <table className="w-full text-sm">
              <thead className="bg-zinc-50 text-left text-xs text-zinc-500">
                <tr>
                  <th className="px-3 py-2 font-medium">Question</th>
                  {ENGINES.map((e) => <th key={e.key} className="px-2 py-2 font-medium">{e.label}</th>)}
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {rows.map((r) => (
                  <tr key={r.id} className={r.status === 'PAUSED' ? 'opacity-60' : ''}>
                    <td className="max-w-xs px-3 py-2 align-top">
                      <div className="text-zinc-900">{r.query}</div>
                      <div className="mt-0.5 text-[11px] text-zinc-400">
                        {r.status === 'PAUSED' ? 'Paused · ' : ''}{lastChecked(r) ? `checked ${fmtDate(lastChecked(r))}` : 'not checked yet'}
                      </div>
                    </td>
                    {ENGINES.map((e) => <td key={e.key} className="px-2 py-2 align-top"><Chip cell={r.cells[e.key]} /></td>)}
                    <td className="whitespace-nowrap px-3 py-2 text-right align-top">
                      <RowActions row={r} canEdit={canEdit} busy={busy} running={!!runningRunId}
                        onHistory={() => setHistoryFor(r)}
                        onRecheck={() => act(`q${r.id}`, async () => { const run = await api.recheckAiQuery(r.id); setRunningRunId(run.id); })}
                        onToggle={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: r.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED' }))}
                        onRemove={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: 'REMOVED' }))} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {/* Mobile cards */}
          <ul className="space-y-2 md:hidden">
            {rows.map((r) => (
              <li key={r.id} className={`rounded-lg border border-zinc-200 bg-white p-3 ${r.status === 'PAUSED' ? 'opacity-60' : ''}`}>
                <div className="text-sm text-zinc-900">{r.query}</div>
                <div className="mt-0.5 text-[11px] text-zinc-400">
                  {r.status === 'PAUSED' ? 'Paused · ' : ''}{lastChecked(r) ? `checked ${fmtDate(lastChecked(r))}` : 'not checked yet'}
                </div>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {ENGINES.map((e) => <Chip key={e.key} cell={r.cells[e.key]} engineLabel={e.short} />)}
                </div>
                <div className="mt-2">
                  <RowActions row={r} canEdit={canEdit} busy={busy} running={!!runningRunId}
                    onHistory={() => setHistoryFor(r)}
                    onRecheck={() => act(`q${r.id}`, async () => { const run = await api.recheckAiQuery(r.id); setRunningRunId(run.id); })}
                    onToggle={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: r.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED' }))}
                    onRemove={() => act(`q${r.id}`, () => api.updateAiQuery(r.id, { status: 'REMOVED' }))} />
                </div>
              </li>
            ))}
          </ul>
        </section>
      ))}

      {canEdit && <AddQuestion count={countedNow} disabled={busy !== null} onAdd={(q) => act('add', () => api.addAiQuery(q, null))} />}

      <PageGapsPanel />

      <section className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2">
        <RankedList title="Who AI recommends instead" hint="Places named on questions where we were missing (last 4 weeks)" items={data.placesInstead} canAddCompetitor={canEdit} />
        <RankedList title="Sources AI reads where we're missing" hint="Sites cited in those answers: get listed or mentioned there" items={data.sourcesInstead} />
      </section>

      {canEdit && <SettingsPanel />}

      {historyFor && <HistorySheet row={historyFor} canEdit={canEdit} onClose={() => setHistoryFor(null)} />}
    </div>
  );
}

function ProposedItem({ row, canEdit, disabled, onApprove, onRemove, onSave }: {
  row: AiQueryRow; canEdit: boolean; disabled: boolean; onApprove: () => void; onRemove: () => void; onSave: (text: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(row.query);
  return (
    <li className="flex flex-col gap-2 py-2 sm:flex-row sm:items-center sm:justify-between">
      {editing ? (
        <input value={text} onChange={(e) => setText(e.target.value)} className="w-full rounded border border-amber-300 px-2 py-1 text-base sm:text-sm" />
      ) : (
        <div className="text-sm text-amber-950">
          {row.query}
          {row.topic && <span className="ml-2 text-[11px] text-amber-700">{row.topic}</span>}
        </div>
      )}
      {canEdit && (
        <div className="flex shrink-0 gap-2">
          {editing ? (
            <>
              <button type="button" disabled={disabled} onClick={() => { setEditing(false); onSave(text); }} className="rounded border border-amber-400 px-2 py-1 text-xs">Save</button>
              <button type="button" onClick={() => { setEditing(false); setText(row.query); }} className="px-2 py-1 text-xs text-amber-800">Cancel</button>
            </>
          ) : (
            <>
              <button type="button" disabled={disabled} onClick={onApprove} className="rounded bg-white px-2 py-1 text-xs font-medium text-amber-900 ring-1 ring-amber-300">Approve</button>
              <button type="button" onClick={() => setEditing(true)} className="px-2 py-1 text-xs text-amber-800">Edit</button>
              <button type="button" disabled={disabled} onClick={onRemove} className="px-2 py-1 text-xs text-amber-800">Remove</button>
            </>
          )}
        </div>
      )}
    </li>
  );
}

function RowActions({ row, canEdit, busy, running, onHistory, onRecheck, onToggle, onRemove }: {
  row: AiQueryRow; canEdit: boolean; busy: string | null; running: boolean;
  onHistory: () => void; onRecheck: () => void; onToggle: () => void; onRemove: () => void;
}) {
  const [confirmRemove, setConfirmRemove] = useState(false);
  const pending = busy === `q${row.id}`;
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs md:justify-end">
      <button type="button" onClick={onHistory} className="font-medium text-zinc-900 underline-offset-2 hover:underline">History</button>
      {canEdit && row.status === 'ACTIVE' && (
        <button type="button" disabled={busy !== null || running} onClick={onRecheck} title="Ask all 5 assistants now (once a day per question)"
          className="text-zinc-500 hover:text-zinc-900 disabled:opacity-40">Re-check</button>
      )}
      {canEdit && <button type="button" disabled={busy !== null} onClick={onToggle} className="text-zinc-500 hover:text-zinc-900 disabled:opacity-40">{row.status === 'PAUSED' ? 'Resume' : 'Pause'}</button>}
      {canEdit && (confirmRemove ? (
        <span className="inline-flex gap-2">
          <button type="button" onClick={() => { setConfirmRemove(false); onRemove(); }} className="font-medium text-red-600">Remove?</button>
          <button type="button" onClick={() => setConfirmRemove(false)} className="text-zinc-500">Keep</button>
        </span>
      ) : (
        <button type="button" disabled={busy !== null} onClick={() => setConfirmRemove(true)} className="text-zinc-400 hover:text-red-600 disabled:opacity-40">Remove</button>
      ))}
      {pending && <Spinner className="h-3 w-3" />}
    </div>
  );
}

function AddQuestion({ count, disabled, onAdd }: { count: number; disabled: boolean; onAdd: (q: string) => void }) {
  const [text, setText] = useState('');
  const full = count >= MAX_TRACKED;
  return (
    <form
      className="flex flex-col gap-2 rounded-lg border border-zinc-200 bg-white p-3 sm:flex-row sm:items-center"
      onSubmit={(e) => { e.preventDefault(); if (text.trim().length >= 8) { onAdd(text.trim()); setText(''); } }}
    >
      <input value={text} onChange={(e) => setText(e.target.value)} disabled={full}
        placeholder={full ? 'Limit reached: pause or remove a question first' : 'Add a question, e.g. "best place for lip blush in San Diego"'}
        className="min-w-0 flex-1 rounded-md border border-zinc-300 px-3 py-2 text-base sm:text-sm" />
      <div className="flex items-center gap-3">
        <span className="text-xs text-zinc-500">{count}/{MAX_TRACKED}</span>
        <button type="submit" disabled={disabled || full || text.trim().length < 8} className="rounded-md bg-zinc-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-40">Add</button>
      </div>
    </form>
  );
}

function AddCompetitor({ name }: { name: string }) {
  const [open, setOpen] = useState(false);
  const [site, setSite] = useState('');
  const [state, setState] = useState<'idle' | 'saving' | 'done' | string>('idle');
  if (state === 'done') return <span className="text-[11px] text-emerald-700">Added to SEO competitors</span>;
  if (!open) return <button type="button" onClick={() => setOpen(true)} className="text-[11px] text-zinc-500 hover:text-zinc-900">Add to competitors</button>;
  return (
    <form className="mt-1 flex gap-1" onSubmit={async (e) => {
      e.preventDefault();
      setState('saving');
      try { await api.addAiCompetitor(name, site); setState('done'); } catch (err) { setState(err instanceof Error ? err.message : 'Failed'); }
    }}>
      <input value={site} onChange={(e) => setSite(e.target.value)} placeholder="their website" className="min-w-0 flex-1 rounded border border-zinc-300 px-2 py-1 text-base sm:text-xs" />
      <button type="submit" disabled={state === 'saving' || site.trim().length < 4} className="rounded bg-zinc-900 px-2 py-1 text-xs text-white disabled:opacity-40">Add</button>
      {state !== 'idle' && state !== 'saving' && <span className="self-center text-[11px] text-red-600">{state}</span>}
    </form>
  );
}

const COMPONENT_HINTS: Record<string, string> = {
  BEFORE_AFTER: 'Real photos only, with client consent',
  FAQ: 'Answers from facts you have confirmed',
  PRICES: 'Same prices as in Square',
  REVIEWS_ON_PAGE: 'Real Google reviews, quoted verbatim',
  RATING_STATED: 'Current Google rating and count',
  ARTIST_BIO: 'Real experience, training and certificates',
  PROCESS_STEPS: 'How a visit actually goes',
  AFTERCARE: 'Your real aftercare instructions',
  VIDEO: 'Your own video of real work',
  BOOKING_CTA: 'A clear Book button near the top',
  FINANCING: 'Only payment options you really offer',
  MAP_NAP: 'Address, phone and map',
  BUSINESS_SCHEMA: 'Technical markup, Claude can add it',
  PRESS: 'Only real publications',
  GUARANTEE: 'Only your actual policy',
  UPDATED_DATE: 'Show when the page was last updated',
};

function pathOf(url: string): string {
  const p = url.replace(/^https?:\/\/[^/]+/, '');
  return p || '/';
}

function PageGapsPanel() {
  const [gaps, setGaps] = useState<AiPageGap[] | null>(null);
  useEffect(() => { api.getAiPageGaps().then(setGaps).catch(() => setGaps([])); }, []);
  return (
    <section className="rounded-lg border border-zinc-200 bg-white p-3 sm:p-4">
      <h2 className="text-sm font-semibold text-zinc-900">What competitor pages have and ours don&rsquo;t</h2>
      <p className="text-xs text-zinc-500">From the pages AI cites for each question. Refreshed monthly; open a question&rsquo;s History for the full comparison.</p>
      {gaps === null && <div className="mt-3"><Spinner className="h-4 w-4" /></div>}
      {gaps && gaps.length === 0 && <p className="mt-3 text-sm text-zinc-400">No gaps found yet. Pages are compared after the first checks.</p>}
      {gaps && gaps.length > 0 && (
        <ul className="mt-3 divide-y divide-zinc-100">
          {gaps.slice(0, 10).map((g) => (
            <li key={g.targetUrl + g.component} className="py-2 text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                <span className="font-medium text-zinc-900">{g.label} <span className="font-normal text-zinc-500">on {pathOf(g.targetUrl)}</span></span>
                <span className="text-xs text-zinc-500">{g.competitorsWith} of {g.competitorsCompared} competitor pages · {g.queries.length} question{g.queries.length === 1 ? '' : 's'}</span>
              </div>
              {COMPONENT_HINTS[g.component] && <div className="text-[11px] text-zinc-400">{COMPONENT_HINTS[g.component]}</div>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function RankedList({ title, hint, items, canAddCompetitor = false }: { title: string; hint: string; items: AiRanked[]; canAddCompetitor?: boolean }) {
  return (
    <div className="min-w-0 rounded-lg border border-zinc-200 bg-white p-3 sm:p-4">
      <h2 className="text-sm font-semibold text-zinc-900">{title}</h2>
      <p className="text-xs text-zinc-500">{hint}</p>
      {items.length === 0 ? (
        <p className="mt-3 text-sm text-zinc-400">No data yet.</p>
      ) : (
        <ol className="mt-3 space-y-2">
          {items.map((it) => (
            <li key={it.name} className="text-sm">
              <div className="flex items-baseline justify-between gap-2">
                <span className="truncate text-zinc-900">{it.name}</span>
                <span className="shrink-0 text-xs text-zinc-500">{it.count}×</span>
              </div>
              {it.queries.length > 0 && <div className="truncate text-[11px] text-zinc-400">{it.queries.slice(0, 2).join(' · ')}{it.queries.length > 2 ? ` +${it.queries.length - 2}` : ''}</div>}
              {canAddCompetitor && <AddCompetitor name={it.name} />}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function HistorySheet({ row, canEdit, onClose }: { row: AiQueryRow; canEdit: boolean; onClose: () => void }) {
  const [tab, setTab] = useState<'checks' | 'pages'>('checks');
  const [items, setItems] = useState<AiHistoryItem[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getAiQueryHistory(row.id).then((h) => { if (!cancelled) setItems(h); }).catch((e) => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load'); });
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { cancelled = true; window.removeEventListener('keydown', onKey); document.body.style.overflow = prevOverflow; };
  }, [row.id, onClose]);

  // Week-by-engine dots: one column per check date (newest right), up to 52.
  const dates = useMemo(() => {
    if (!items) return [];
    const set = new Set(items.map((i) => i.checkedAt.slice(0, 10)));
    return [...set].sort().slice(-52);
  }, [items]);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 md:items-stretch md:justify-end" onClick={onClose} role="dialog" aria-modal="true" aria-label="Check history">
      <div className="flex max-h-[88vh] w-full flex-col rounded-t-2xl bg-white shadow-xl md:max-h-none md:w-[32rem] md:rounded-none" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-start justify-between gap-3 border-b border-zinc-200 p-4">
          <div>
            <div className="text-xs font-medium uppercase tracking-wide text-zinc-500">History</div>
            <div className="text-sm font-semibold text-zinc-900">{row.query}</div>
            <div className="mt-2 flex gap-1">
              {(['checks', 'pages'] as const).map((t) => (
                <button key={t} type="button" onClick={() => setTab(t)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium ${tab === t ? 'bg-zinc-900 text-white' : 'bg-zinc-100 text-zinc-600'}`}>
                  {t === 'checks' ? 'Checks' : 'Page comparison'}
                </button>
              ))}
            </div>
          </div>
          <button type="button" onClick={onClose} className="rounded-md px-2 py-1 text-lg leading-none text-zinc-500 hover:bg-zinc-100" aria-label="Close">×</button>
        </div>
        <div className="flex-1 overflow-y-auto p-4" style={{ paddingBottom: 'calc(1rem + env(safe-area-inset-bottom, 0px))' }}>
          {tab === 'pages' && <PageComparison queryId={row.id} canEdit={canEdit} />}
          {tab === 'checks' && err && <p className="text-sm text-red-600">{err}</p>}
          {tab === 'checks' && !items && !err && <div className="flex items-center gap-2 text-sm text-zinc-500"><Spinner className="h-4 w-4" /> Loading…</div>}
          {tab === 'checks' && items && items.length === 0 && <p className="text-sm text-zinc-500">No checks yet. The first one runs on Monday.</p>}
          {tab === 'checks' && items && items.length > 0 && (
            <>
              <div className="overflow-x-auto">
                <table className="text-[11px]">
                  <tbody>
                    {ENGINES.map((e) => (
                      <tr key={e.key}>
                        <td className="pr-2 text-zinc-500">{e.short}</td>
                        {dates.map((d) => {
                          const c = items.find((i) => i.engine === e.key && i.checkedAt.slice(0, 10) === d);
                          const color = !c ? 'bg-zinc-100' : c.status !== 'OK' ? 'bg-zinc-300' : c.mentioned ? 'bg-emerald-500' : 'bg-red-400';
                          return <td key={d} className="p-0.5"><span title={`${d}${c ? ` · ${c.status === 'OK' ? (c.mentioned ? 'named' : 'not named') : c.status.toLowerCase()}` : ''}`} className={`block h-3 w-3 rounded-sm ${color}`} /></td>;
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="mt-4 divide-y divide-zinc-100">
                {items.map((i) => (
                  <li key={i.id} className="py-3">
                    <button type="button" className="flex w-full items-center justify-between gap-2 text-left" onClick={() => setOpen(open === i.id ? null : i.id)}>
                      <span className="text-sm text-zinc-900">
                        {ENGINES.find((e) => e.key === i.engine)?.label} · {fmtDate(i.checkedAt)}
                      </span>
                      <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${i.status !== 'OK' ? 'bg-zinc-100 text-zinc-500' : i.mentioned ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-700'}`}>
                        {i.status !== 'OK' ? (i.status === 'ERROR' ? 'Error' : 'No answer') : i.mentioned ? (i.rank ? `Named #${i.rank}` : 'Named') : 'Not named'}
                      </span>
                    </button>
                    {i.placesNamed.length > 0 && <div className="mt-1 text-xs text-zinc-600">Named: {i.placesNamed.join(', ')}</div>}
                    {open === i.id && (
                      <div className="mt-2 space-y-2 text-xs text-zinc-600">
                        {i.ownSiteCited && <div className="text-emerald-700">Our website was cited as a source.</div>}
                        {i.sourcesCited.length > 0 && (
                          <div>
                            <div className="font-medium text-zinc-700">Sources</div>
                            <ul className="mt-1 space-y-0.5">
                              {i.sourcesCited.map((s, idx) => (
                                <li key={idx} className="truncate">
                                  {s.url ? <a href={s.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">{s.domain}</a> : s.domain}
                                  {s.title ? <span className="text-zinc-400"> · {s.title}</span> : null}
                                </li>
                              ))}
                            </ul>
                          </div>
                        )}
                        {i.subQueries.length > 0 && <div><span className="font-medium text-zinc-700">It searched for:</span> {i.subQueries.join(' · ')}</div>}
                        {i.excerpt && <p className="whitespace-pre-line rounded bg-zinc-50 p-2 text-zinc-700">{i.excerpt}</p>}
                        {i.error && <div className="text-red-600">{i.error}</div>}
                        {i.model && <div className="text-zinc-400">Model: {i.model}</div>}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

const COMPONENT_ORDER = ['BEFORE_AFTER', 'FAQ', 'PRICES', 'REVIEWS_ON_PAGE', 'RATING_STATED', 'ARTIST_BIO', 'PROCESS_STEPS', 'AFTERCARE',
  'VIDEO', 'BOOKING_CTA', 'FINANCING', 'MAP_NAP', 'BUSINESS_SCHEMA', 'PRESS', 'GUARANTEE', 'UPDATED_DATE'];
const COMPONENT_LABELS: Record<string, string> = {
  BEFORE_AFTER: 'Before/after', FAQ: 'FAQ', PRICES: 'Prices', REVIEWS_ON_PAGE: 'Reviews', RATING_STATED: 'Rating', ARTIST_BIO: 'Artist bio',
  PROCESS_STEPS: 'Process', AFTERCARE: 'Aftercare', VIDEO: 'Video', BOOKING_CTA: 'Book button', FINANCING: 'Financing', MAP_NAP: 'Map/address',
  BUSINESS_SCHEMA: 'Schema', PRESS: 'Press', GUARANTEE: 'Guarantee', UPDATED_DATE: 'Updated date',
};

function hostOf(url: string): string {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return url; }
}

function PageComparison({ queryId, canEdit }: { queryId: number; canEdit: boolean }) {
  const [data, setData] = useState<AiPageComparison | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [evidence, setEvidence] = useState<string | null>(null);
  useEffect(() => { api.getAiPageComparison(queryId).then(setData).catch((e) => setErr(e instanceof Error ? e.message : 'Failed to load')); }, [queryId]);
  if (err) return <p className="text-sm text-red-600">{err}</p>;
  if (!data) return <div className="flex items-center gap-2 text-sm text-zinc-500"><Spinner className="h-4 w-4" /> Loading…</div>;
  const cols = [...(data.ours ? [data.ours] : []), ...data.competitors];
  return (
    <div className="space-y-3">
      {data.gaps.length > 0 && (
        <div className="rounded-md bg-amber-50 p-2 text-xs text-amber-900">
          Missing on our page: {data.gaps.map((g) => `${COMPONENT_LABELS[g.component] ?? g.label} (${g.competitorsWith}/${g.competitorsCompared}${g.weak ? ', weak' : ''})`).join(', ')}
        </div>
      )}
      {!data.targetUrl && <p className="text-xs text-zinc-500">No target page set for this question.</p>}
      {data.competitors.length === 0 && <p className="text-xs text-zinc-500">No competitor pages cited for this question in the last 4 weeks.</p>}
      {cols.length > 0 && (
        <div className="overflow-x-auto">
          <table className="text-xs">
            <thead>
              <tr>
                <th className="sticky left-0 bg-white pr-2 text-left font-medium text-zinc-500" />
                {cols.map((c) => (
                  <th key={c.url} className="px-1.5 pb-1 text-left align-bottom font-medium">
                    <a href={c.url} target="_blank" rel="noreferrer" className="block max-w-[7rem] truncate hover:underline" title={c.url}>
                      <span className={c.own ? 'text-emerald-800' : 'text-zinc-700'}>{c.own ? 'Our page' : hostOf(c.url)}</span>
                    </a>
                    <span className="block text-[10px] font-normal text-zinc-400">
                      {!c.lastFetchedAt ? 'not fetched yet' : !c.readable ? 'not readable' : !c.own && !c.businessPage ? 'not a business page' : fmtDate(c.lastFetchedAt)}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {COMPONENT_ORDER.map((k) => (
                <tr key={k} className="border-t border-zinc-100">
                  <td className="sticky left-0 whitespace-nowrap bg-white py-1 pr-2 text-zinc-600">{COMPONENT_LABELS[k]}</td>
                  {cols.map((c) => {
                    const f = c.components[k];
                    return (
                      <td key={c.url} className="px-1.5 py-1 text-center">
                        {!c.readable ? <span className="text-zinc-300">·</span> : f?.present ? (
                          <button type="button" onClick={() => setEvidence(`${c.own ? 'Our page' : hostOf(c.url)}: ${f.evidence ?? ''}`)} className="text-emerald-600" aria-label="Present, show evidence">✓</button>
                        ) : <span className={c.own ? 'text-red-500' : 'text-zinc-300'}>✗</span>}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {evidence && <p className="rounded bg-zinc-50 p-2 text-[11px] text-zinc-600">{evidence}</p>}
      <div className="flex flex-wrap items-center gap-3 text-[11px] text-zinc-400">
        <span>{data.lastRefreshedAt ? `Pages read ${fmtDate(data.lastRefreshedAt)}` : 'Pages not read yet'}</span>
        {canEdit && (
          <button type="button" className="text-zinc-600 hover:text-zinc-900" onClick={async () => {
            setMsg(null);
            try { await api.refreshAiPages(queryId); setMsg('Reading pages in the background, reopen in a minute.'); } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); }
          }}>Refresh pages</button>
        )}
        {msg && <span className="text-zinc-600">{msg}</span>}
      </div>
    </div>
  );
}

function SettingsPanel() {
  const [open, setOpen] = useState(false);
  const [s, setS] = useState<AiVisibilitySettingsDto | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [aliases, setAliases] = useState('');
  const [domains, setDomains] = useState('');
  const [budget, setBudget] = useState('');

  useEffect(() => {
    if (!open || s) return;
    api.getAiVisibilitySettings().then((v) => {
      setS(v);
      setAliases(v.brandAliases.join(', '));
      setDomains(v.ownDomains.join(', '));
      setBudget(String(v.monthlyBudgetUsd));
    }).catch((e) => setMsg(e instanceof Error ? e.message : 'Failed to load'));
  }, [open, s]);

  async function save(digestEnabled?: boolean) {
    setMsg(null);
    try {
      const v = await api.updateAiVisibilitySettings({
        brandAliases: aliases.split(',').map((a) => a.trim()).filter(Boolean),
        ownDomains: domains.split(',').map((d) => d.trim()).filter(Boolean),
        monthlyBudgetUsd: Number(budget),
        ...(digestEnabled !== undefined ? { digestEnabled } : {}),
      });
      setS(v);
      setMsg('Saved');
    } catch (e) {
      setMsg(e instanceof Error ? e.message : 'Save failed');
    }
  }

  return (
    <section className="rounded-lg border border-zinc-200 bg-white">
      <button type="button" onClick={() => setOpen(!open)} className="flex w-full items-center justify-between p-3 text-left text-sm font-medium text-zinc-900 sm:p-4">
        Settings <span className="text-zinc-400">{open ? '−' : '+'}</span>
      </button>
      {open && (
        <div className="space-y-3 border-t border-zinc-100 p-3 text-sm sm:p-4">
          {!s && !msg && <Spinner className="h-4 w-4" />}
          {s && (
            <>
              <label className="block">
                <span className="text-xs text-zinc-500">Names that count as us (comma separated)</span>
                <input value={aliases} onChange={(e) => setAliases(e.target.value)} className="mt-1 w-full rounded-md border border-zinc-300 px-3 py-2 text-base sm:text-sm" />
              </label>
              <label className="block">
                <span className="text-xs text-zinc-500">Our domains (subdomains count too)</span>
                <input value={domains} onChange={(e) => setDomains(e.target.value)} className="mt-1 w-full rounded-md border border-zinc-300 px-3 py-2 text-base sm:text-sm" />
              </label>
              <label className="block max-w-[12rem]">
                <span className="text-xs text-zinc-500">Monthly budget, USD</span>
                <input inputMode="decimal" value={budget} onChange={(e) => setBudget(e.target.value)} className="mt-1 w-full rounded-md border border-zinc-300 px-3 py-2 text-base sm:text-sm" />
              </label>
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={s.digestEnabled} onChange={(e) => save(e.target.checked)} />
                <span>Weekly Telegram summary</span>
              </label>
              <button type="button" onClick={() => save()} className="rounded-md bg-zinc-900 px-3 py-2 text-sm font-medium text-white">Save</button>
            </>
          )}
          {msg && <p className="text-xs text-zinc-600">{msg}</p>}
        </div>
      )}
    </section>
  );
}
