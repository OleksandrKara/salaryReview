'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { TwilioSmsSettingsDto } from '../../../lib/types';

export default function TwilioSmsSettingsForm({ initialSettings }: { initialSettings: TwilioSmsSettingsDto }) {
  const [settings, setSettings] = useState(initialSettings);
  // These three start blank regardless of whether they're already set — GET only ever returns
  // masked values, and PUTting one back would overwrite the real credential with the literal
  // masked string.
  const [accountSidInput, setAccountSidInput] = useState('');
  const [apiKeyInput, setApiKeyInput] = useState('');
  const [apiSecretInput, setApiSecretInput] = useState('');
  const [fromPhoneNumberInput, setFromPhoneNumberInput] = useState(initialSettings.fromPhoneNumber ?? '');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateTwilioSmsSettings({
        // Only send a field if the owner actually typed into it — undefined means "leave
        // unchanged" server-side (see TwilioSmsConfigService.update's null-vs-empty contract).
        accountSid: accountSidInput === '' ? undefined : accountSidInput,
        apiKey: apiKeyInput === '' ? undefined : apiKeyInput,
        apiSecret: apiSecretInput === '' ? undefined : apiSecretInput,
        fromPhoneNumber: fromPhoneNumberInput,
      });
      setSettings(updated);
      setAccountSidInput('');
      setApiKeyInput('');
      setApiSecretInput('');
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
        <span className="mb-1 block text-zinc-600">Account SID</span>
        <input
          type="password"
          value={accountSidInput}
          onChange={(e) => setAccountSidInput(e.target.value)}
          placeholder={settings.accountSidSet ? `Currently set: ${settings.accountSidMasked} — leave blank to keep it` : 'Not set'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">API Key</span>
        <input
          type="password"
          value={apiKeyInput}
          onChange={(e) => setApiKeyInput(e.target.value)}
          placeholder={settings.apiKeySet ? `Currently set: ${settings.apiKeyMasked} — leave blank to keep it` : 'Not set'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">API Secret</span>
        <input
          type="password"
          value={apiSecretInput}
          onChange={(e) => setApiSecretInput(e.target.value)}
          placeholder={settings.apiSecretSet ? `Currently set: ${settings.apiSecretMasked} — leave blank to keep it` : 'Not set'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">From phone number</span>
        <input
          value={fromPhoneNumberInput}
          onChange={(e) => setFromPhoneNumberInput(e.target.value)}
          placeholder="e.g. +15551234567"
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
          {saving ? 'Saving…' : 'Save'}
        </button>
        {saved && <span className="text-sm text-green-700">Saved.</span>}
      </div>

      <p className="text-xs text-zinc-400">
        Last updated {settings.updatedAt ? new Date(settings.updatedAt).toLocaleString() : 'never'}
        {settings.updatedBy ? ` by ${settings.updatedBy}` : ''}.
      </p>
    </form>
  );
}
