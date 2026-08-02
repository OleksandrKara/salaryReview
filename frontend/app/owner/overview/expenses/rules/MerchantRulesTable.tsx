'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../../lib/api';
import type { MerchantRule } from '../../../../lib/types';

const TYPE_LABEL: Record<string, string> = {
  FINGERPRINT: 'Fingerprint',
  MERCHANT: 'Merchant',
  MERCHANT_KEYWORD: 'Merchant + keyword',
  MERCHANT_AMOUNT_RANGE: 'Merchant + amount range',
};

function RuleRow({ rule, onChanged }: { rule: MerchantRule; onChanged: () => void }) {
  const [editing, setEditing] = useState(false);
  const [category, setCategory] = useState(rule.category);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function save() {
    setBusy(true);
    setError('');
    try {
      await api.updateMerchantRule(rule.id, { category });
      setEditing(false);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save.');
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive() {
    setBusy(true);
    setError('');
    try {
      await api.updateMerchantRule(rule.id, { active: !rule.active });
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update.');
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!window.confirm(`Delete this rule for ${rule.normalizedMerchant}?`)) return;
    setBusy(true);
    setError('');
    try {
      await api.deleteMerchantRule(rule.id);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete.');
      setBusy(false);
    }
  }

  return (
    <div className={`rounded-lg p-3 ring-1 ${rule.active ? 'ring-zinc-200' : 'bg-zinc-50 ring-zinc-100'}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="text-sm font-medium text-zinc-800">{rule.normalizedMerchant}</span>{' '}
          <span className="text-xs text-zinc-400">({TYPE_LABEL[rule.ruleType] ?? rule.ruleType})</span>
          {!rule.active && <span className="ml-1.5 text-xs text-zinc-400">— inactive</span>}
          {rule.keyword && <div className="text-xs text-zinc-500">Keyword: {rule.keyword}</div>}
          {(rule.amountMin !== null || rule.amountMax !== null) && (
            <div className="text-xs text-zinc-500">Amount: {rule.amountMin ?? '—'} to {rule.amountMax ?? '—'}</div>
          )}
          <div className="text-xs text-zinc-400">Applied {rule.timesApplied} time{rule.timesApplied === 1 ? '' : 's'}</div>
        </div>
        <div className="flex items-center gap-2">
          {editing ? (
            <>
              <input
                type="text"
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                className="w-40 rounded border border-zinc-300 px-1.5 py-1 text-xs"
              />
              <button type="button" disabled={busy} onClick={save} className="rounded bg-zinc-800 px-2 py-1 text-xs font-medium text-white hover:bg-zinc-700 disabled:opacity-50">
                Save
              </button>
              <button type="button" disabled={busy} onClick={() => { setEditing(false); setCategory(rule.category); }} className="text-xs text-zinc-500 hover:underline">
                Cancel
              </button>
            </>
          ) : (
            <>
              <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-medium text-zinc-600 ring-1 ring-inset ring-zinc-200">
                {rule.category}
              </span>
              <button type="button" onClick={() => setEditing(true)} className="text-xs text-blue-600 hover:underline">Edit</button>
              <button type="button" disabled={busy} onClick={toggleActive} className="text-xs text-zinc-500 hover:underline disabled:opacity-50">
                {rule.active ? 'Deactivate' : 'Activate'}
              </button>
              <button type="button" disabled={busy} onClick={remove} className="text-xs text-red-600 hover:underline disabled:opacity-50">
                Delete
              </button>
            </>
          )}
        </div>
      </div>
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  );
}

export default function MerchantRulesTable() {
  const [rules, setRules] = useState<MerchantRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load() {
    try {
      setRules(await api.listMerchantRules());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load merchant rules.');
    } finally {
      setLoading(false);
    }
  }

  // Initial fetch on mount — a plain .then() chain, not the reusable `load` above (called from
  // event handlers after a mutation), so state updates happen as a subscription callback rather
  // than synchronously inside the effect body.
  useEffect(() => {
    let cancelled = false;
    api.listMerchantRules()
      .then((result) => { if (!cancelled) setRules(result); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load merchant rules.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  if (loading) return <p className="mt-6 text-sm text-zinc-500">Loading…</p>;

  return (
    <div className="mt-4 flex flex-col gap-2">
      {error && <p className="text-sm text-red-600">{error}</p>}
      {rules.length === 0 ? (
        <p className="text-sm text-zinc-500">No rules learned yet — they&apos;re created automatically as you reconcile statements.</p>
      ) : (
        rules.map((r) => <RuleRow key={r.id} rule={r} onChanged={load} />)
      )}
    </div>
  );
}
