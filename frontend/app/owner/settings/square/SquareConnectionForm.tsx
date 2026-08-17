'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { SquareConnectionDto } from '../../../lib/types';

export default function SquareConnectionForm({ initialConnection }: { initialConnection: SquareConnectionDto }) {
  const [connection, setConnection] = useState(initialConnection);
  // Access token always starts blank — GET only ever returns a masked value, and PUTting that back
  // would overwrite the real token with the literal masked string (same contract as Telegram's bot
  // token field).
  const [accessTokenInput, setAccessTokenInput] = useState('');
  const [environmentInput, setEnvironmentInput] = useState<'SANDBOX' | 'PRODUCTION'>(
    connection.environment ?? 'PRODUCTION',
  );
  const [locationIdInput, setLocationIdInput] = useState(connection.locationId ?? '');
  const [applicationIdInput, setApplicationIdInput] = useState(connection.applicationId ?? '');
  // Same blank-starts / undefined-means-keep-existing contract as accessTokenInput above.
  const [webhookSignatureKeyInput, setWebhookSignatureKeyInput] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateSquareConnection({
        // Only sent if the owner actually typed a new one — undefined means "keep the existing
        // token" server-side (see SquareConnectionService.connect's null-vs-unchanged contract).
        accessToken: accessTokenInput === '' ? undefined : accessTokenInput,
        environment: environmentInput,
        locationId: locationIdInput,
        applicationId: applicationIdInput === '' ? undefined : applicationIdInput,
        webhookSignatureKey: webhookSignatureKeyInput === '' ? undefined : webhookSignatureKeyInput,
      });
      setConnection(updated);
      setAccessTokenInput('');
      setWebhookSignatureKeyInput('');
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
        <span className="mb-1 block text-zinc-600">Environment</span>
        <select
          value={environmentInput}
          onChange={(e) => setEnvironmentInput(e.target.value as 'SANDBOX' | 'PRODUCTION')}
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        >
          <option value="PRODUCTION">Production</option>
          <option value="SANDBOX">Sandbox</option>
        </select>
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Access token</span>
        <input
          type="password"
          value={accessTokenInput}
          onChange={(e) => setAccessTokenInput(e.target.value)}
          placeholder={connection.accessTokenSet ? `Currently set: ${connection.accessTokenMasked} — leave blank to keep it` : 'Not set'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Location ID</span>
        <input
          value={locationIdInput}
          onChange={(e) => setLocationIdInput(e.target.value)}
          placeholder="e.g. 8Z286R826"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Application ID (optional, for reference only)</span>
        <input
          value={applicationIdInput}
          onChange={(e) => setApplicationIdInput(e.target.value)}
          placeholder="e.g. sq0idp-..."
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>

      <div className="rounded border border-zinc-200 bg-zinc-50 p-3">
        <p className="mb-1 text-sm font-medium text-zinc-700">Webhook</p>
        <p className="mb-2 text-xs text-zinc-500">
          In Square&apos;s Developer Dashboard for this business, add a webhook subscription for the{' '}
          <code className="rounded bg-zinc-200 px-1">payment.updated</code> event with this exact
          Notification URL, then paste the Signature Key it gives you below.
        </p>
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Notification URL</span>
          <input
            readOnly
            value={connection.webhookNotificationUrl}
            onFocus={(e) => e.currentTarget.select()}
            className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 font-mono text-xs"
          />
        </label>
        <label className="mt-3 block text-sm">
          <span className="mb-1 block text-zinc-600">Signature Key</span>
          <input
            type="password"
            value={webhookSignatureKeyInput}
            onChange={(e) => setWebhookSignatureKeyInput(e.target.value)}
            placeholder={
              connection.webhookSignatureKeySet
                ? `Currently set: ${connection.webhookSignatureKeyMasked} — leave blank to keep it`
                : 'Not set — checkout-review/rebooking-discount texts won\'t fire until this is set'
            }
            autoComplete="off"
            className="w-full rounded border border-zinc-300 px-2 py-1.5"
          />
        </label>
      </div>

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
        {connection.merchantId && <p>Merchant ID: {connection.merchantId}</p>}
        <p>
          Last connected {connection.connectedAt ? new Date(connection.connectedAt).toLocaleString() : 'never'}.
        </p>
      </div>
    </form>
  );
}
