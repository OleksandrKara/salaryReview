'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { PromoTermsDto } from '../../../lib/types';

// Owner-editable discount amount/minimum-spend for one coupon (see PromoConfigService) — extracted
// out of the former standalone "Coupon discounts" section so it can render inside whichever
// automation card(s) actually use this promo code (see AutomationsPanel's AUTOMATION_PROMO_CODES —
// WINBACK5 is used by two automations, REBOOK10 by one). The first save for a business creates
// real objects in that business's own Square account (Customer Group + Discount + Pricing Rule) —
// see PromoConfigService — so that one requires an explicit second click to confirm; every save
// after that just updates the amounts in place.
export default function PromoTermsEditor({ terms, onSaved }: { terms: PromoTermsDto; onSaved: (t: PromoTermsDto) => void }) {
  const [discountAmount, setDiscountAmount] = useState(terms.discountAmount === null ? '' : String(terms.discountAmount));
  const [minSpend, setMinSpend] = useState(terms.minSpend === null ? '' : String(terms.minSpend));
  const [saving, setSaving] = useState(false);
  const [confirmingSetup, setConfirmingSetup] = useState(false);
  const [error, setError] = useState('');

  const dirty = discountAmount !== (terms.discountAmount === null ? '' : String(terms.discountAmount))
    || minSpend !== (terms.minSpend === null ? '' : String(terms.minSpend));

  async function save() {
    const amount = Number(discountAmount);
    if (!discountAmount || Number.isNaN(amount) || amount <= 0) {
      setError('Enter a discount amount greater than 0');
      return;
    }
    if (!terms.configured && !confirmingSetup) {
      // First save for this business — require an explicit second click before writing to Square.
      setConfirmingSetup(true);
      return;
    }
    setSaving(true);
    setError('');
    try {
      const parsedMinSpend = minSpend === '' ? null : Number(minSpend);
      const updated = await api.updatePromoTerms(terms.promoCode, amount, parsedMinSpend);
      onSaved(updated);
      setConfirmingSetup(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-zinc-700">{terms.label}</span>
        {!terms.configured && <span className="text-xs text-amber-600">Not set up yet — link 404s until saved</span>}
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs text-zinc-500">
          <span className="mb-1 block">Discount ($)</span>
          <input
            type="number"
            min="0"
            step="0.01"
            value={discountAmount}
            onChange={(e) => {
              setDiscountAmount(e.target.value);
              setConfirmingSetup(false);
            }}
            placeholder="e.g. 15"
            className="w-28 rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
          />
        </label>
        <label className="text-xs text-zinc-500">
          <span className="mb-1 block">Minimum spend ($, blank = none)</span>
          <input
            type="number"
            min="0"
            step="0.01"
            value={minSpend}
            onChange={(e) => {
              setMinSpend(e.target.value);
              setConfirmingSetup(false);
            }}
            placeholder="e.g. 300"
            className="w-32 rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
          />
        </label>
        <button
          type="button"
          onClick={save}
          disabled={saving || (!dirty && terms.configured)}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
        >
          {saving && <Spinner className="h-3 w-3" />}
          {saving ? 'Saving…' : confirmingSetup ? 'Confirm — create in Square' : terms.configured ? 'Save' : 'Set up in Square'}
        </button>
      </div>
      {confirmingSetup && !saving && (
        <p className="mt-2 text-xs text-amber-600">
          This creates a real Customer Group, Discount, and Pricing Rule in your connected Square account.
          Click again to confirm.
        </p>
      )}
      {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
    </div>
  );
}
