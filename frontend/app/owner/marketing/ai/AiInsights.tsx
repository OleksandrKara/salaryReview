'use client';

import { useEffect, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../../../lib/api';
import type { AiEngine, AiProgress, AiTraffic, AiTrends } from '../../../lib/types';
import { Spinner } from '../../../components/Spinner';

const ENGINE_LINES: { key: AiEngine; label: string; color: string }[] = [
  { key: 'CHATGPT', label: 'ChatGPT', color: '#10a37f' },
  { key: 'GOOGLE_AI_OVERVIEW', label: 'AI Overview', color: '#4285f4' },
  { key: 'GOOGLE_AI_MODE', label: 'AI Mode', color: '#a142f4' },
  { key: 'GEMINI', label: 'Gemini', color: '#f29900' },
  { key: 'PERPLEXITY', label: 'Perplexity', color: '#20808d' },
  { key: 'CLAUDE', label: 'Claude', color: '#d97757' },
];
const PLACE_COLORS = ['#a1a1aa', '#f87171', '#fbbf24', '#60a5fa', '#c084fc'];

function weekLabel(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

function money(n: number): string {
  return `$${Math.round(n).toLocaleString('en-US')}`;
}

/** "From AI to clients": AI-referred visitors -> leads -> booked -> paying clients -> revenue. */
export function AiTrafficPanel() {
  const [days, setDays] = useState(90);
  const [data, setData] = useState<AiTraffic | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    api.getAiTraffic(days).then((d) => { if (!cancelled) setData(d); }).catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load'); });
    return () => { cancelled = true; };
  }, [days]);

  const steps = data ? [
    { label: 'Visitors from AI', value: String(data.visitors) },
    { label: 'Leads', value: String(data.leads) },
    { label: 'Booked', value: String(data.booked) },
    { label: 'Paying clients', value: `${data.paidClients}${data.newClients > 0 ? ` (${data.newClients} new)` : ''}` },
    { label: 'Revenue', value: money(Number(data.revenue)) },
  ] : [];

  return (
    <section className="min-w-0 rounded-lg border border-zinc-200 bg-white p-3 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold text-zinc-900">From AI to clients</h2>
          <p className="text-xs text-zinc-500">People who came from ChatGPT, Perplexity, Gemini, Copilot or Claude, and what they paid (Square, tips excluded).</p>
        </div>
        <div role="radiogroup" aria-label="Period" className="inline-flex rounded-full bg-zinc-100 p-1">
          {[30, 90, 365].map((d) => (
            <button key={d} type="button" role="radio" aria-checked={days === d} onClick={() => { if (d !== days) { setData(null); setDays(d); } }}
              className={`min-h-[32px] rounded-full px-3 text-xs font-medium ${days === d ? 'bg-white text-zinc-900 shadow-sm' : 'text-zinc-500'}`}>
              {d === 365 ? '1 year' : `${d} days`}
            </button>
          ))}
        </div>
      </div>
      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      {!data && !error && <div className="mt-3"><Spinner className="h-4 w-4" /></div>}
      {data && (
        <>
          <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
            {steps.map((s, i) => (
              <div key={s.label} className="rounded-md bg-zinc-50 p-2">
                <div className="text-[11px] text-zinc-500">{i > 0 ? '→ ' : ''}{s.label}</div>
                <div className="text-lg font-semibold text-zinc-900">{s.value}</div>
              </div>
            ))}
          </div>
          {Object.keys(data.visitorsBySource).length > 0 && (
            <p className="mt-2 text-xs text-zinc-500">
              {Object.entries(data.visitorsBySource).map(([k, v]) => `${k} ${v}`).join(' · ')}
            </p>
          )}
          {data.weekly.length > 1 && (
            <div className="mt-3 h-40 w-full">
              <ResponsiveContainer>
                <BarChart data={data.weekly.map((w) => ({ week: weekLabel(w.weekStart), visitors: w.visitors }))} margin={{ top: 4, right: 4, left: -24, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
                  <XAxis dataKey="week" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                  <Tooltip />
                  <Bar dataKey="visitors" name="Visitors from AI" fill="#18181b" radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          {data.visitors === 0 && <p className="mt-2 text-sm text-zinc-400">No visitors from AI assistants in this period.</p>}
        </>
      )}
    </section>
  );
}

function Sparkline({ values }: { values: (number | null)[] }) {
  const w = 96, h = 24;
  const pts = values.map((v, i) => (v === null ? null : [values.length === 1 ? w / 2 : (i * (w - 4)) / (values.length - 1) + 2, h - 2 - (v / 100) * (h - 4)] as const));
  const path = pts.filter((p): p is readonly [number, number] => p !== null).map((p) => p.join(',')).join(' ');
  const last = [...pts].reverse().find((p) => p !== null);
  return (
    <svg width={w} height={h} role="img" aria-label={`Weekly: ${values.map((v) => (v === null ? 'no data' : `${v}%`)).join(', ')}`}>
      <polyline points={path} fill="none" stroke="#18181b" strokeWidth={1.5} />
      {last && <circle cx={last[0]} cy={last[1]} r={2.5} fill="#18181b" />}
    </svg>
  );
}

/** Headline progress: is AI naming us more or less often than last week, and how the curve moves. */
export function AiProgressPanel({ site }: { site?: string }) {
  const [engine, setEngine] = useState<AiEngine | ''>('');
  const [data, setData] = useState<AiProgress | null>(null);
  useEffect(() => {
    let cancelled = false;
    api.getAiProgress(site, engine || undefined, 12)
      .then((d) => { if (!cancelled) setData(d); })
      .catch(() => { if (!cancelled) setData({ weeks: [], delta: null, queries: [], weeksAvailable: 0 }); });
    return () => { cancelled = true; };
  }, [site, engine]);

  const weeks = data?.weeks ?? [];
  const latest = weeks.length ? weeks[weeks.length - 1] : null;
  const rows = weeks.map((w) => ({ week: weekLabel(w.weekStart), named: w.mentionedPercent, cited: w.citedPercent }));
  const d = data?.delta ?? null;
  const badge = d && (d.direction === 'up' ? 'bg-emerald-50 text-emerald-700' : d.direction === 'down' ? 'bg-red-50 text-red-700' : 'bg-zinc-100 text-zinc-600');
  const arrow = d ? (d.direction === 'up' ? '▲' : d.direction === 'down' ? '▼' : '■') : '';

  return (
    <section className="min-w-0 rounded-lg border border-zinc-200 bg-white p-3 sm:p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold text-zinc-900">Progress by week</h2>
          <p className="text-xs text-zinc-500">
            Named: share of answers where AI recommends us. Cited: share that link to our own site. Compared week to week on the same questions and assistants.
          </p>
        </div>
        <select aria-label="Assistant" value={engine} onChange={(e) => setEngine(e.target.value as AiEngine | '')}
          className="min-h-[36px] rounded-md border border-zinc-300 bg-white px-2 text-sm">
          <option value="">All assistants</option>
          {ENGINE_LINES.map((e) => <option key={e.key} value={e.key}>{e.label}</option>)}
        </select>
      </div>
      {!data && <div className="mt-3"><Spinner className="h-4 w-4" /></div>}
      {data && latest && (
        <div className="mt-3 flex flex-wrap items-end gap-x-6 gap-y-2">
          <div>
            <div className="text-3xl font-semibold text-zinc-900">{latest.mentionedPercent ?? '-'}%</div>
            <div className="text-xs text-zinc-500">named, week of {weekLabel(latest.weekStart)} ({latest.answers} answers)</div>
          </div>
          {d && (
            <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${badge}`} title={`${d.comparedPairs} question and assistant pairs checked in both weeks`}>
              {arrow} {d.mentionedPoints > 0 ? '+' : ''}{d.mentionedPoints} pts vs week of {weekLabel(d.from)}
            </span>
          )}
          <div className="text-xs text-zinc-600">
            <div>Cited our site: <b>{latest.citedPercent ?? '-'}%</b>{d ? ` (${d.citedPoints > 0 ? '+' : ''}${d.citedPoints} pts)` : ''}</div>
            <div>Average position when named: <b>{latest.avgRank ?? '-'}</b></div>
          </div>
        </div>
      )}
      {data && weeks.length < 2 && <p className="mt-3 text-sm text-zinc-400">{weeks.length === 0 ? 'No checks yet.' : 'The curve appears after two weeks of checks.'}</p>}
      {data && weeks.length >= 2 && (
        <div className="mt-3 h-56 w-full sm:h-64">
          <ResponsiveContainer>
            <LineChart data={rows} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
              <XAxis dataKey="week" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
              <YAxis domain={[0, 100]} unit="%" tick={{ fontSize: 11 }} />
              <Tooltip formatter={(v) => (v === null || v === undefined ? 'no data' : `${v}%`)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Line type="monotone" dataKey="named" name="Named" stroke="#18181b" strokeWidth={3} dot={{ r: 3 }} connectNulls />
              <Line type="monotone" dataKey="cited" name="Cited our site" stroke="#10a37f" strokeWidth={2} dot={{ r: 2 }} connectNulls />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      {data && weeks.length >= 2 && data.queries.length > 0 && (
        <details className="mt-3">
          <summary className="cursor-pointer text-sm font-medium text-zinc-700">By question ({data.queries.length})</summary>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full min-w-[420px] text-left text-sm">
              <thead className="text-xs text-zinc-500">
                <tr><th className="py-1 pr-2 font-medium">Question</th><th className="px-2 font-medium">Trend</th><th className="pl-2 text-right font-medium">Now</th></tr>
              </thead>
              <tbody>
                {data.queries.map((q) => {
                  const now = [...q.mentionedPercent].reverse().find((v) => v !== null) ?? null;
                  return (
                    <tr key={q.queryId} className="border-t border-zinc-100">
                      <td className="py-1.5 pr-2 text-zinc-800">{q.text}</td>
                      <td className="px-2"><Sparkline values={q.mentionedPercent} /></td>
                      <td className="pl-2 text-right tabular-nums">{now === null ? '-' : `${now}%`}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  );
}

/** Weekly visibility per engine and share of answers naming us vs the most-named competitors. */
export function AiTrendsPanel() {
  const [data, setData] = useState<AiTrends | null>(null);
  const [mode, setMode] = useState<'engines' | 'competitors'>('engines');
  useEffect(() => {
    let cancelled = false;
    api.getAiTrends(12).then((d) => { if (!cancelled) setData(d); }).catch(() => { if (!cancelled) setData({ weeks: [], topPlaces: [] }); });
    return () => { cancelled = true; };
  }, []);

  const rows = (data?.weeks ?? []).map((w) => ({
    week: weekLabel(w.weekStart),
    us: w.usPercent,
    ...Object.fromEntries(ENGINE_LINES.map((e) => [e.key, w.enginePercent[e.key] ?? null])),
    ...Object.fromEntries((data?.topPlaces ?? []).map((p) => [p, w.placePercent[p] ?? 0])),
  }));

  return (
    <section className="min-w-0 rounded-lg border border-zinc-200 bg-white p-3 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold text-zinc-900">Trend by week</h2>
          <p className="text-xs text-zinc-500">{mode === 'engines' ? 'Share of answers that named us, per assistant.' : 'Share of answers naming us vs the places AI names most often.'}</p>
        </div>
        <div role="radiogroup" aria-label="Chart" className="inline-flex rounded-full bg-zinc-100 p-1">
          {(['engines', 'competitors'] as const).map((m) => (
            <button key={m} type="button" role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
              className={`min-h-[32px] rounded-full px-3 text-xs font-medium ${mode === m ? 'bg-white text-zinc-900 shadow-sm' : 'text-zinc-500'}`}>
              {m === 'engines' ? 'By assistant' : 'Vs competitors'}
            </button>
          ))}
        </div>
      </div>
      {!data && <div className="mt-3"><Spinner className="h-4 w-4" /></div>}
      {data && rows.length < 2 && <p className="mt-3 text-sm text-zinc-400">The chart appears after two weeks of checks.</p>}
      {data && rows.length >= 2 && (
        <div className="mt-3 h-56 w-full sm:h-64">
          <ResponsiveContainer>
            <LineChart data={rows} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e4e4e7" vertical={false} />
              <XAxis dataKey="week" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
              <YAxis domain={[0, 100]} unit="%" tick={{ fontSize: 11 }} />
              <Tooltip formatter={(v) => (v === null || v === undefined ? 'no data' : `${v}%`)} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {mode === 'engines'
                ? ENGINE_LINES.map((e) => <Line key={e.key} type="monotone" dataKey={e.key} name={e.label} stroke={e.color} strokeWidth={2} dot={{ r: 2 }} connectNulls />)
                : [
                    <Line key="us" type="monotone" dataKey="us" name="Us" stroke="#18181b" strokeWidth={3} dot={{ r: 3 }} />,
                    ...(data.topPlaces).map((p, i) => <Line key={p} type="monotone" dataKey={p} name={p} stroke={PLACE_COLORS[i % PLACE_COLORS.length]} strokeWidth={1.5} dot={{ r: 2 }} />),
                  ]}
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
