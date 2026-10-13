'use client';

import { useEffect, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../../../lib/api';
import type { AiEngine, AiTraffic, AiTrends } from '../../../lib/types';
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
