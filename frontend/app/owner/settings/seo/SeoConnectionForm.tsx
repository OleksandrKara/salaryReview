'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { SeoConnectionDto } from '../../../lib/types';

export default function SeoConnectionForm({ initialConnection }: { initialConnection: SeoConnectionDto }) {
  const [connection, setConnection] = useState(initialConnection);
  // Both credential fields always start blank — GET only ever returns a masked/derived value, and
  // PUTting that back would overwrite the real secret with the literal masked string (same
  // contract as Square's access-token field).
  const [serviceAccountJsonInput, setServiceAccountJsonInput] = useState('');
  const [ga4PropertyIdInput, setGa4PropertyIdInput] = useState(connection.ga4PropertyId ?? '');
  const [ga4MeasurementIdInput, setGa4MeasurementIdInput] = useState(connection.ga4MeasurementId ?? '');
  const [pagespeedApiKeyInput, setPagespeedApiKeyInput] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateSeoConnection({
        gscServiceAccountJson: serviceAccountJsonInput === '' ? undefined : serviceAccountJsonInput,
        ga4PropertyId: ga4PropertyIdInput,
        ga4MeasurementId: ga4MeasurementIdInput,
        pagespeedApiKey: pagespeedApiKeyInput === '' ? undefined : pagespeedApiKeyInput,
      });
      setConnection(updated);
      setServiceAccountJsonInput('');
      setPagespeedApiKeyInput('');
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
        <span className="mb-1 block text-zinc-600">Search Console service-account JSON</span>
        <textarea
          value={serviceAccountJsonInput}
          onChange={(e) => setServiceAccountJsonInput(e.target.value)}
          placeholder={
            connection.serviceAccountSet
              ? `Currently set: ${connection.serviceAccountEmail} — leave blank to keep it`
              : 'Paste the full JSON key file downloaded from Google Cloud'
          }
          rows={6}
          autoComplete="off"
          spellCheck={false}
          className="w-full rounded border border-zinc-300 px-2 py-1.5 font-mono text-xs"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">GA4 property ID</span>
        <input
          value={ga4PropertyIdInput}
          onChange={(e) => setGa4PropertyIdInput(e.target.value)}
          placeholder="e.g. 552140452"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">GA4 measurement ID</span>
        <input
          value={ga4MeasurementIdInput}
          onChange={(e) => setGa4MeasurementIdInput(e.target.value)}
          placeholder="e.g. G-XXXXXXXXXX"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">PageSpeed Insights API key</span>
        <input
          type="password"
          value={pagespeedApiKeyInput}
          onChange={(e) => setPagespeedApiKeyInput(e.target.value)}
          placeholder={
            connection.pagespeedApiKeySet
              ? `Currently set: ${connection.pagespeedApiKeyMasked} — leave blank to keep it`
              : 'Not set'
          }
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {saving && <Spinner className="h-4 w-4" />}
          {saving ? 'Verifying & saving…' : 'Save'}
        </button>
        {saved && <span className="text-sm text-green-700">Saved.</span>}
      </div>

      <div className="text-xs text-zinc-400">
        <p>
          Last synced {connection.lastSyncAt ? new Date(connection.lastSyncAt).toLocaleString() : 'never'}.
        </p>
        {connection.lastSyncError && <p className="mt-1 text-amber-600">Last sync error: {connection.lastSyncError}</p>}
      </div>
    </form>
  );
}
