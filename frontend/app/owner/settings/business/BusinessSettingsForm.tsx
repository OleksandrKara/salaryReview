'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { BusinessSettingsDto } from '../../../lib/types';

// Rates are stored as decimals (0.45) but shown/entered as percent (45) — matches how an owner
// actually thinks about a commission rate.
function toPercentString(rate: number | null): string {
  return rate === null ? '' : (rate * 100).toString();
}

export default function BusinessSettingsForm({ initialSettings }: { initialSettings: BusinessSettingsDto }) {
  const [settings, setSettings] = useState(initialSettings);
  const [name, setName] = useState(settings.name);
  const [timezone, setTimezone] = useState(settings.timezone);
  const [ownerShortName, setOwnerShortName] = useState(settings.ownerShortName ?? '');
  const [baseCommissionPct, setBaseCommissionPct] = useState(toPercentString(settings.baseCommissionRate));
  const [tierEnabled, setTierEnabled] = useState(settings.tierEnabled);
  const [tierServiceThreshold, setTierServiceThreshold] = useState(
    settings.tierServiceThreshold === null ? '' : String(settings.tierServiceThreshold),
  );
  const [servicePriceCutoff, setServicePriceCutoff] = useState(
    settings.servicePriceCutoff === null ? '' : String(settings.servicePriceCutoff),
  );
  const [cardTipFeePct, setCardTipFeePct] = useState(toPercentString(settings.cardTipFeeRate));
  const [noShowFeeAmount, setNoShowFeeAmount] = useState(
    settings.noShowFeeAmount === null ? '' : String(settings.noShowFeeAmount),
  );
  const [restrictDiscountCoverage, setRestrictDiscountCoverage] = useState(settings.restrictDiscountCoverage);
  const [coveredDiscountNames, setCoveredDiscountNames] = useState(settings.coveredDiscountNames ?? '');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateBusinessSettings({
        name: name || undefined,
        timezone: timezone || undefined,
        ownerShortName: ownerShortName || undefined,
        baseCommissionRate: baseCommissionPct === '' ? undefined : Number(baseCommissionPct) / 100,
        tierEnabled,
        tierServiceThreshold: tierServiceThreshold === '' ? undefined : Number(tierServiceThreshold),
        servicePriceCutoff: servicePriceCutoff === '' ? undefined : Number(servicePriceCutoff),
        cardTipFeeRate: cardTipFeePct === '' ? undefined : Number(cardTipFeePct) / 100,
        noShowFeeAmount: noShowFeeAmount === '' ? undefined : Number(noShowFeeAmount),
        restrictDiscountCoverage,
        coveredDiscountNames: restrictDiscountCoverage ? (coveredDiscountNames || undefined) : undefined,
      });
      setSettings(updated);
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} className="mt-6 flex flex-col gap-4 rounded-lg ring-1 ring-zinc-200 p-4">
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Business name</span>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Short code (fixed, not editable)</span>
        <input
          value={settings.shortCode}
          disabled
          className="w-full rounded border border-zinc-200 bg-zinc-50 px-2 py-1.5 text-zinc-500"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Timezone</span>
        <input
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          placeholder="e.g. America/Los_Angeles"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Auto-set from Square&rsquo;s own location record the next time you connect/reconnect Square, if set there.
        </span>
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Owner short name (used in #salary messages)</span>
        <input
          value={ownerShortName}
          onChange={(e) => setOwnerShortName(e.target.value)}
          placeholder="e.g. AK"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Base commission rate (%)</span>
        <input
          type="number"
          step="0.01"
          value={baseCommissionPct}
          onChange={(e) => setBaseCommissionPct(e.target.value)}
          placeholder="e.g. 45"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={tierEnabled} onChange={(e) => setTierEnabled(e.target.checked)} />
        <span className="text-zinc-600">Tier bonus program enabled</span>
      </label>
      <p className="-mt-2 text-xs text-zinc-400">
        When off, every provider is paid the flat base rate always — no exceptions, including manual grants.
      </p>

      {tierEnabled && (
        <>
          <label className="text-sm">
            <span className="mb-1 block text-zinc-600">Tier service threshold (services/month)</span>
            <input
              type="number"
              value={tierServiceThreshold}
              onChange={(e) => setTierServiceThreshold(e.target.value)}
              placeholder="e.g. 60"
              className="w-full rounded border border-zinc-300 px-2 py-1.5"
            />
          </label>
        </>
      )}

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Service price cutoff ($, 0 = every service counts)</span>
        <input
          type="number"
          step="0.01"
          value={servicePriceCutoff}
          onChange={(e) => setServicePriceCutoff(e.target.value)}
          placeholder="0"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Card tip fee rate (%)</span>
        <input
          type="number"
          step="0.01"
          value={cardTipFeePct}
          onChange={(e) => setCardTipFeePct(e.target.value)}
          placeholder="e.g. 3.5"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">No-show fee ($, blank = no no-show fee program)</span>
        <input
          type="number"
          step="0.01"
          value={noShowFeeAmount}
          onChange={(e) => setNoShowFeeAmount(e.target.value)}
          placeholder="e.g. 25"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Once set, this can be changed but not cleared back to &ldquo;no program&rdquo; from this form.
        </span>
      </label>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={restrictDiscountCoverage}
          onChange={(e) => setRestrictDiscountCoverage(e.target.checked)}
        />
        <span className="text-zinc-600">Only cover specific discounts (default: cover every discount)</span>
      </label>
      <p className="-mt-2 text-xs text-zinc-400">
        Off (default): providers are paid commission on the full menu price regardless of any Square discount
        applied — the salon absorbs it. On: only discounts whose name matches the list below are absorbed;
        every other discount (ordinary promos, coupons) instead reduces the provider&rsquo;s commission basis
        down to what was actually collected.
      </p>

      {restrictDiscountCoverage && (
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Covered discount names (comma-separated, case-insensitive)</span>
          <input
            value={coveredDiscountNames}
            onChange={(e) => setCoveredDiscountNames(e.target.value)}
            placeholder="e.g. deposit"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
          <span className="mt-1 block text-xs text-zinc-400">
            Matched as a substring against each Square discount&rsquo;s own name — e.g. &ldquo;deposit&rdquo;
            matches a discount named &ldquo;Deposit &rdquo;. Blank means no discount is covered at all.
          </span>
        </label>
      )}

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {saving && <Spinner className="h-4 w-4" />}
          {saving ? 'Saving…' : 'Save'}
        </button>
        {saved && <span className="text-sm text-green-700">Saved.</span>}
      </div>

      {!settings.configured && (
        <p className="text-xs text-amber-600">
          Financial config isn&rsquo;t set up yet — owner short name, base commission rate, and card tip fee rate
          are required the first time you save.
        </p>
      )}
    </form>
  );
}
