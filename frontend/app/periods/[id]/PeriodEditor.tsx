'use client';

import { useMemo, useState } from 'react';
import { api } from '../../lib/api';
import type { PeriodEntry, Provider, Settlement } from '../../lib/types';

type Row = {
  procedures: string;
  cardTotal: string;
  cashTotal: string;
  cardTips: string;
  adjustmentsAmount: string;
  adjustmentsNote: string;
  /** Empty string = no override, fall back to provider default. Otherwise PERCENTAGE (e.g. "45" not "0.45"). */
  ratePct: string;
};

const EMPTY: Row = {
  procedures: '0',
  cardTotal: '0',
  cashTotal: '0',
  cardTips: '0',
  adjustmentsAmount: '0',
  adjustmentsNote: '',
  ratePct: '',
};

function rowFromEntry(e: PeriodEntry): Row {
  return {
    procedures: String(e.procedures ?? 0),
    cardTotal: String(e.cardTotal ?? 0),
    cashTotal: String(e.cashTotal ?? 0),
    cardTips: String(e.cardTips ?? 0),
    adjustmentsAmount: String(e.adjustmentsAmount ?? 0),
    adjustmentsNote: e.adjustmentsNote ?? '',
    ratePct: e.commissionRate != null ? String(Math.round(e.commissionRate * 10000) / 100) : '',
  };
}

export default function PeriodEditor({
  periodId,
  providers,
  initialEntries,
}: {
  periodId: number;
  providers: Provider[];
  initialEntries: PeriodEntry[];
}) {
  const activeProviders = useMemo(
    () => providers.filter((p) => p.active).sort((a, b) => a.displayName.localeCompare(b.displayName)),
    [providers],
  );

  // rows keyed by providerId
  const [rows, setRows] = useState<Record<number, Row>>(() => {
    const byProvider: Record<number, Row> = {};
    for (const p of activeProviders) byProvider[p.id] = { ...EMPTY };
    for (const e of initialEntries) byProvider[e.providerId] = rowFromEntry(e);
    return byProvider;
  });

  const [savingProviderId, setSavingProviderId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [settlements, setSettlements] = useState<Settlement[] | null>(null);
  const [calculating, setCalculating] = useState(false);

  function updateField(providerId: number, field: keyof Row, value: string) {
    setRows((prev) => ({
      ...prev,
      [providerId]: { ...prev[providerId], [field]: value },
    }));
  }

  async function saveRow(providerId: number) {
    const row = rows[providerId];
    if (!row) return;
    setSavingProviderId(providerId);
    setError(null);
    try {
      const trimmedRate = row.ratePct.trim();
      const commissionRate = trimmedRate === '' ? null : Number(trimmedRate) / 100;
      await api.upsertEntry(periodId, providerId, {
        procedures: Number(row.procedures) || 0,
        cardTotal: Number(row.cardTotal) || 0,
        cashTotal: Number(row.cashTotal) || 0,
        cardTips: Number(row.cardTips) || 0,
        adjustmentsAmount: Number(row.adjustmentsAmount) || 0,
        adjustmentsNote: row.adjustmentsNote || null,
        commissionRate,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSavingProviderId(null);
    }
  }

  async function calculate() {
    setCalculating(true);
    setError(null);
    try {
      const out = await api.getSettlements(periodId);
      setSettlements(out);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setCalculating(false);
    }
  }

  async function copyToClipboard(text: string) {
    await navigator.clipboard.writeText(text);
  }

  return (
    <div className="grid gap-8 md:grid-cols-[1fr_400px]">
      {/* Editor table */}
      <section>
        <h2 className="mb-3 text-lg font-medium">Entries</h2>
        <div className="overflow-x-auto rounded border border-zinc-200">
          <table className="w-full border-collapse text-sm">
            <thead className="bg-zinc-50 text-left text-zinc-600">
              <tr>
                <th className="px-3 py-2">Provider</th>
                <th className="px-3 py-2"># proc</th>
                <th className="px-3 py-2">Card $</th>
                <th className="px-3 py-2">Cash $</th>
                <th className="px-3 py-2">Card tips $</th>
                <th className="px-3 py-2">Adj $</th>
                <th className="px-3 py-2">Adj note</th>
                <th className="px-3 py-2" title="Per-period commission %. Blank = use provider default.">Rate %</th>
              </tr>
            </thead>
            <tbody>
              {activeProviders.map((p) => {
                const row = rows[p.id] ?? EMPTY;
                const saving = savingProviderId === p.id;
                return (
                  <tr key={p.id} className="border-t border-zinc-200">
                    <td className="px-3 py-2 font-medium">
                      {p.displayName}
                      {saving && <span className="ml-2 text-xs text-zinc-400">saving…</span>}
                    </td>
                    <NumCell value={row.procedures} onChange={(v) => updateField(p.id, 'procedures', v)} onBlur={() => saveRow(p.id)} step="1" />
                    <NumCell value={row.cardTotal} onChange={(v) => updateField(p.id, 'cardTotal', v)} onBlur={() => saveRow(p.id)} />
                    <NumCell value={row.cashTotal} onChange={(v) => updateField(p.id, 'cashTotal', v)} onBlur={() => saveRow(p.id)} />
                    <NumCell value={row.cardTips} onChange={(v) => updateField(p.id, 'cardTips', v)} onBlur={() => saveRow(p.id)} />
                    <NumCell value={row.adjustmentsAmount} onChange={(v) => updateField(p.id, 'adjustmentsAmount', v)} onBlur={() => saveRow(p.id)} allowNegative />
                    <td className="px-3 py-2">
                      <input
                        type="text"
                        value={row.adjustmentsNote}
                        onChange={(e) => updateField(p.id, 'adjustmentsNote', e.target.value)}
                        onBlur={() => saveRow(p.id)}
                        className="w-full rounded border border-zinc-300 px-2 py-1"
                      />
                    </td>
                    <td className="px-3 py-2">
                      <input
                        type="number"
                        inputMode="decimal"
                        step="0.5"
                        min={0}
                        max={100}
                        placeholder={(p.commissionRate * 100).toFixed(1)}
                        value={row.ratePct}
                        onChange={(e) => updateField(p.id, 'ratePct', e.target.value)}
                        onBlur={() => saveRow(p.id)}
                        className="w-20 rounded border border-zinc-300 px-2 py-1 text-right"
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <button
          onClick={calculate}
          disabled={calculating}
          className="mt-4 rounded bg-zinc-900 px-5 py-2 text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {calculating ? 'Calculating…' : 'Calculate'}
        </button>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
      </section>

      {/* Settlements */}
      <section>
        <h2 className="mb-3 text-lg font-medium">Settlements</h2>
        {!settlements && <p className="text-sm text-zinc-500">Hit Calculate to render messages.</p>}
        {settlements && (
          <div className="space-y-4">
            {settlements.map((s) => (
              <div key={s.providerId} className="rounded border border-zinc-200 bg-white p-3">
                <div className="mb-2 flex items-baseline justify-between">
                  <span className="font-medium">{s.providerName}</span>
                  <button
                    onClick={() => copyToClipboard(s.messageText)}
                    className="text-xs text-zinc-500 hover:text-zinc-900"
                  >
                    Copy
                  </button>
                </div>
                <pre className="whitespace-pre-wrap font-mono text-xs text-zinc-700">{s.messageText}</pre>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function NumCell({
  value,
  onChange,
  onBlur,
  step = '0.01',
  allowNegative = false,
}: {
  value: string;
  onChange: (v: string) => void;
  onBlur: () => void;
  step?: string;
  allowNegative?: boolean;
}) {
  return (
    <td className="px-3 py-2">
      <input
        type="number"
        inputMode="decimal"
        step={step}
        min={allowNegative ? undefined : 0}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        className="w-24 rounded border border-zinc-300 px-2 py-1 text-right"
      />
    </td>
  );
}
