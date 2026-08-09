'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '../../lib/api';
import type { BankStatementImportSummary, ExpenseCategory, ExpenseCategoryDefinition, ExpenseEntry } from '../../lib/types';

// PROVIDER_PAYROLL is reconciliation-only (a real bank-statement-derived commission payout) — the
// manual entry form never offers it, matching the previous hardcoded list's own scope.
const MANUAL_ENTRY_EXCLUDED_CODE = 'PROVIDER_PAYROLL';

const usdExact = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// Parses a plain yyyy-MM-dd as local, not UTC-shifted (a UTC parse of a bare date can land on the
// previous day in western timezones).
function parseLocalDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function mondayOnOrBefore(d: Date): Date {
  const day = d.getDay(); // 0 = Sunday .. 6 = Saturday
  const diff = day === 0 ? 6 : day - 1; // days since the preceding Monday
  const monday = new Date(d);
  monday.setDate(d.getDate() - diff);
  return monday;
}

function thisWeekRange(): { from: string; to: string } {
  const monday = mondayOnOrBefore(new Date());
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  return { from: isoDate(monday), to: isoDate(sunday) };
}

function thisMonthRange(): { from: string; to: string } {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  const to = new Date(today.getFullYear(), today.getMonth() + 1, 0);
  return { from: isoDate(from), to: isoDate(to) };
}

function monthToDateSoFarRange(): { from: string; to: string } {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  return { from: isoDate(from), to: isoDate(today) };
}

function fmtDateRange(fromIso: string, toIso: string): string {
  const from = parseLocalDate(fromIso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  const to = parseLocalDate(toIso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  return `${from} – ${to}`;
}

function categoryLabel(c: string, categories: ExpenseCategoryDefinition[]): string {
  return categories.find((x) => x.code === c)?.label ?? c;
}

// Ranges overlap when neither is entirely before the other — inclusive on both ends, since
// statement periods and manual entries are both whole-day-inclusive ranges.
function rangesOverlap(aFrom: string, aTo: string, bFrom: string, bTo: string): boolean {
  return aFrom <= bTo && bFrom <= aTo;
}

/** Whether [from, to] overlaps any COMPLETED statement import's period — once true, this month's
 * expenses are meant to come exclusively from that reconciliation, not a fresh manual entry
 * (openspec design.md D11). A completed import with no detected date range (rare) is treated as
 * covering everything, matching the backend's own conservative interpretation. */
function isStatementCovered(imports: BankStatementImportSummary[], from: string, to: string): boolean {
  return imports.some((imp) => {
    if (imp.status !== 'COMPLETED') return false;
    if (!imp.statementPeriodStart || !imp.statementPeriodEnd) return true;
    return rangesOverlap(from, to, imp.statementPeriodStart, imp.statementPeriodEnd);
  });
}

function PeriodTypeButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-md px-2.5 py-1 text-xs font-medium ${
        active ? 'bg-white text-zinc-800 shadow-sm' : 'text-zinc-500 hover:text-zinc-700'
      }`}
    >
      {label}
    </button>
  );
}

/** Owner-only business-expense entry (materials/rent/utilities/other) feeding the Overview tab's
 * net revenue figure — same flexible-ledger UI shape as the Ads Report's AdSpendEntryForm, minus
 * the per-page scoping (expenses are salon-wide, see the backend migration's own comment). */
export default function ExpenseEntryForm() {
  const [category, setCategory] = useState<ExpenseCategory>('MATERIALS');
  const [categories, setCategories] = useState<ExpenseCategoryDefinition[]>([]);
  const [preset, setPreset] = useState<'week' | 'month' | 'mtd' | 'custom'>('week');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [entries, setEntries] = useState<ExpenseEntry[]>([]);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState('');
  const [completedImports, setCompletedImports] = useState<BankStatementImportSummary[]>([]);
  // The exact "from|to" period the owner has explicitly confirmed entering despite statement
  // coverage — compared against the current period during render instead of reset via an effect,
  // so picking a new period naturally requires a fresh confirmation with no extra state sync.
  const [confirmedPeriod, setConfirmedPeriod] = useState<string | null>(null);

  async function loadEntries() {
    try {
      const result = await api.listExpenseEntries();
      setEntries(result);
    } catch {
      // Non-critical — the form itself still works without the recent-entries list.
    }
  }

  useEffect(() => {
    let cancelled = false;
    api.listExpenseEntries().then((result) => { if (!cancelled) setEntries(result); }).catch(() => {});
    // Best-effort — if this fails, the D11 warning below just doesn't show; it never blocks entry.
    api.listStatementImports().then((result) => { if (!cancelled) setCompletedImports(result); }).catch(() => {});
    api.listExpenseCategories()
      .then((result) => { if (!cancelled) setCategories(result.filter((c) => c.code !== MANUAL_ENTRY_EXCLUDED_CODE)); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const coveredByStatement = from && to && isStatementCovered(completedImports, from, to);
  const confirmedDespiteCoverage = confirmedPeriod === `${from}|${to}`;

  function selectPreset(p: 'week' | 'month' | 'mtd' | 'custom') {
    setPreset(p);
    if (p === 'week') {
      const r = thisWeekRange(); setFrom(r.from); setTo(r.to);
    } else if (p === 'month') {
      const r = thisMonthRange(); setFrom(r.from); setTo(r.to);
    } else if (p === 'mtd') {
      const r = monthToDateSoFarRange(); setFrom(r.from); setTo(r.to);
    }
    // 'custom' leaves from/to for the owner to pick below.
  }

  async function submit() {
    const value = Number(amount);
    if (!from || !to) { setError('Pick a period.'); return; }
    if (!Number.isFinite(value) || value < 0) { setError('Enter a valid, non-negative amount.'); return; }
    setBusy(true);
    setError('');
    try {
      await api.createExpenseEntry(category, from, to, value, note || undefined);
      setAmount('');
      setNote('');
      setSavedAt(Date.now());
      void loadEntries();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save expense.');
    } finally {
      setBusy(false);
    }
  }

  async function saveEdit(id: number, editCategory: ExpenseCategory, editFrom: string, editTo: string, editAmount: number, editNote: string) {
    setRowBusyId(id);
    setRowError('');
    try {
      await api.updateExpenseEntry(id, editCategory, editFrom, editTo, editAmount, editNote || undefined);
      setEditingId(null);
      void loadEntries();
    } catch (e) {
      setRowError(e instanceof Error ? e.message : 'Failed to save changes.');
    } finally {
      setRowBusyId(null);
    }
  }

  async function deleteEntry(id: number) {
    if (!window.confirm('Delete this expense entry? This can\'t be undone.')) return;
    setRowBusyId(id);
    setRowError('');
    try {
      await api.deleteExpenseEntry(id);
      void loadEntries();
    } catch (e) {
      setRowError(e instanceof Error ? e.message : 'Failed to delete entry.');
    } finally {
      setRowBusyId(null);
    }
  }

  return (
    <div className="mt-8 border-t border-zinc-100 pt-8">
      <h2 className="text-sm font-medium text-zinc-500">Enter an expense</h2>
      <p className="mt-1 text-xs text-zinc-400">
        Fixing an outright mistake? Edit or delete the entry below instead — save a new row here only
        for a genuine revision you want kept in the history.
      </p>

      <div className="mt-3 flex flex-wrap items-end gap-3 rounded-lg p-3 ring-1 ring-zinc-200">
        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Category</span>
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as ExpenseCategory)}
            className="rounded border border-zinc-300 px-2 py-1.5 text-xs"
          >
            {categories.map((c) => (
              <option key={c.code} value={c.code}>{c.label}</option>
            ))}
          </select>
          {category === 'MANAGER_TIME' && (
            <span className="max-w-[16rem] text-[11px] leading-snug text-zinc-400">
              Only needed for months before automatic time tracking (clocked hours already cover
              July 2026 onward).
            </span>
          )}
        </label>

        <div className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Period</span>
          <div className="flex flex-wrap gap-1 rounded-lg bg-zinc-100 p-1">
            <PeriodTypeButton label="This week" active={preset === 'week'} onClick={() => selectPreset('week')} />
            <PeriodTypeButton label="This month" active={preset === 'month'} onClick={() => selectPreset('month')} />
            <PeriodTypeButton label="Month-to-date" active={preset === 'mtd'} onClick={() => selectPreset('mtd')} />
            <PeriodTypeButton label="Custom" active={preset === 'custom'} onClick={() => selectPreset('custom')} />
          </div>
        </div>

        {preset === 'custom' && (
          <>
            <label className="flex flex-col gap-1 text-xs">
              <span className="font-medium text-zinc-500">From</span>
              <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)}
                className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
            </label>
            <label className="flex flex-col gap-1 text-xs">
              <span className="font-medium text-zinc-500">To</span>
              <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)}
                className="rounded border border-zinc-300 px-2 py-1.5 text-xs" />
            </label>
          </>
        )}

        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Amount</span>
          <div className="flex items-center gap-1">
            <span className="text-zinc-400">$</span>
            <input
              type="number"
              min="0"
              step="0.01"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="w-28 rounded border border-zinc-300 px-2 py-1.5 text-sm"
            />
          </div>
        </label>

        <label className="flex flex-col gap-1 text-xs">
          <span className="font-medium text-zinc-500">Note (optional)</span>
          <input
            type="text"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. OPI restock"
            className="w-40 rounded border border-zinc-300 px-2 py-1.5 text-xs"
          />
        </label>

        <button
          type="button"
          disabled={busy || (!!coveredByStatement && !confirmedDespiteCoverage)}
          onClick={submit}
          className="rounded bg-zinc-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
        >
          {busy ? 'Saving…' : 'Save'}
        </button>
        {savedAt !== null && !busy && <span className="text-xs text-emerald-600">Saved</span>}
      </div>
      {error ? <p className="mt-2 text-sm text-red-600">{error}</p> : null}
      {from && to && <p className="mt-2 text-xs text-zinc-400">{fmtDateRange(from, to)}</p>}
      {coveredByStatement && (
        <div className="mt-2 flex flex-wrap items-center gap-2 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-amber-200">
          <span>
            This period is already reconciled from an imported statement — entering it here risks
            double-counting. Add or correct it from the{' '}
            <Link href="/owner/overview/expenses/history" className="font-medium underline">
              reconciliation screen
            </Link>{' '}
            instead.
          </span>
          {!confirmedDespiteCoverage && (
            <button
              type="button"
              onClick={() => setConfirmedPeriod(`${from}|${to}`)}
              className="ml-auto whitespace-nowrap rounded bg-amber-100 px-2 py-1 font-medium hover:bg-amber-200"
            >
              Enter it anyway
            </button>
          )}
        </div>
      )}

      {entries.length > 0 && (
        <div className="mt-4">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Recent entries</h3>
          {rowError ? <p className="mt-1 text-xs text-red-600">{rowError}</p> : null}
          <div className="mt-2 flex flex-col gap-1 text-xs text-zinc-600">
            {entries.slice(0, 8).map((e) => (
              <ExpenseEntryRow
                key={e.id}
                entry={e}
                editing={editingId === e.id}
                busy={rowBusyId === e.id}
                onEdit={() => { setEditingId(e.id); setRowError(''); }}
                onCancelEdit={() => setEditingId(null)}
                onSaveEdit={(c, f, t, amt, n) => saveEdit(e.id, c, f, t, amt, n)}
                onDelete={() => deleteEntry(e.id)}
                categories={categories}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** One "Recent entries" row — a read-only line by default, switching to an inline edit form
 * (category + period + amount + note, same fields as the create form above) when its Edit button
 * is clicked. */
function ExpenseEntryRow({
  entry, editing, busy, onEdit, onCancelEdit, onSaveEdit, onDelete, categories,
}: {
  entry: ExpenseEntry;
  editing: boolean;
  busy: boolean;
  onEdit: () => void;
  onCancelEdit: () => void;
  onSaveEdit: (category: ExpenseCategory, from: string, to: string, amount: number, note: string) => void;
  onDelete: () => void;
  categories: ExpenseCategoryDefinition[];
}) {
  const [editCategory, setEditCategory] = useState<ExpenseCategory>(entry.category);
  const [editFrom, setEditFrom] = useState(entry.periodStart);
  const [editTo, setEditTo] = useState(entry.periodEnd);
  const [editAmount, setEditAmount] = useState(String(entry.amount));
  const [editNote, setEditNote] = useState(entry.note ?? '');

  if (!editing) {
    return (
      <div className="flex items-center justify-between gap-2 rounded px-2 py-1 ring-1 ring-zinc-100">
        <span>
          <span className="font-medium text-zinc-700">{categoryLabel(entry.category, categories)}</span>{' '}
          {fmtDateRange(entry.periodStart, entry.periodEnd)}
          {entry.note ? <span className="text-zinc-400"> — {entry.note}</span> : null}
        </span>
        <div className="flex items-center gap-2">
          <span className="font-medium tabular-nums">{usdExact(entry.amount)}</span>
          <button type="button" onClick={onEdit} className="text-blue-600 hover:underline">Edit</button>
          <button type="button" onClick={onDelete} disabled={busy} className="text-red-600 hover:underline disabled:opacity-50">
            Delete
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-end gap-2 rounded px-2 py-1.5 ring-1 ring-blue-200">
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">Category</span>
        <select value={editCategory} onChange={(e) => setEditCategory(e.target.value as ExpenseCategory)}
          className="rounded border border-zinc-300 px-1.5 py-1">
          {categories.map((c) => (
            <option key={c.code} value={c.code}>{c.label}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">From</span>
        <input type="date" value={editFrom} max={editTo} onChange={(e) => setEditFrom(e.target.value)}
          className="rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">To</span>
        <input type="date" value={editTo} min={editFrom} onChange={(e) => setEditTo(e.target.value)}
          className="rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">Amount</span>
        <input type="number" min="0" step="0.01" inputMode="decimal" value={editAmount}
          onChange={(e) => setEditAmount(e.target.value)} className="w-24 rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <label className="flex flex-col gap-0.5">
        <span className="text-zinc-500">Note</span>
        <input type="text" value={editNote} onChange={(e) => setEditNote(e.target.value)}
          className="w-32 rounded border border-zinc-300 px-1.5 py-1" />
      </label>
      <button
        type="button"
        disabled={busy}
        onClick={() => onSaveEdit(editCategory, editFrom, editTo, Number(editAmount), editNote)}
        className="rounded bg-zinc-800 px-2 py-1 font-medium text-white hover:bg-zinc-700 disabled:opacity-50"
      >
        {busy ? 'Saving…' : 'Save'}
      </button>
      <button type="button" disabled={busy} onClick={onCancelEdit} className="text-zinc-500 hover:underline disabled:opacity-50">
        Cancel
      </button>
    </div>
  );
}
