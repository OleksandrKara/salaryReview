'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { TelegramSettingsDto } from '../../../lib/types';

export default function TelegramSettingsForm({ initialSettings }: { initialSettings: TelegramSettingsDto }) {
  const [settings, setSettings] = useState(initialSettings);
  // Starts blank regardless of whether a token is already set — GET only ever returns a masked
  // value, and PUTting that back would overwrite the real token with the literal masked string.
  const [botTokenInput, setBotTokenInput] = useState('');
  const [chatIdInput, setChatIdInput] = useState(initialSettings.chatId ?? '');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateTelegramSettings({
        // Only send botToken if the owner actually typed into that field — undefined means
        // "leave unchanged" server-side (see TelegramConfigService.update's null-vs-empty contract).
        botToken: botTokenInput === '' ? undefined : botTokenInput,
        chatId: chatIdInput,
      });
      setSettings(updated);
      setBotTokenInput('');
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
        <span className="mb-1 block text-zinc-600">Bot token</span>
        <input
          type="password"
          value={botTokenInput}
          onChange={(e) => setBotTokenInput(e.target.value)}
          placeholder={settings.botTokenSet ? `Currently set: ${settings.botTokenMasked} — leave blank to keep it` : 'Not set'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Chat ID</span>
        <input
          value={chatIdInput}
          onChange={(e) => setChatIdInput(e.target.value)}
          placeholder="e.g. 720950699"
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
