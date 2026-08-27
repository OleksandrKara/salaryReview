'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { MailchimpSettingsDto } from '../../../lib/types';

export default function MailchimpSettingsForm({ initialSettings }: { initialSettings: MailchimpSettingsDto }) {
  const [settings, setSettings] = useState(initialSettings);
  // Starts blank regardless of whether it's already set — GET only ever returns a masked value,
  // and PUTting one back would overwrite the real key with the literal masked string.
  const [apiKeyInput, setApiKeyInput] = useState('');
  const [audienceIdInput, setAudienceIdInput] = useState(initialSettings.audienceId ?? '');
  const [fromNameInput, setFromNameInput] = useState(initialSettings.fromName ?? '');
  const [fromEmailInput, setFromEmailInput] = useState(initialSettings.fromEmail ?? '');
  const [replyToEmailInput, setReplyToEmailInput] = useState(initialSettings.replyToEmail ?? '');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const updated = await api.updateMailchimpSettings({
        apiKey: apiKeyInput === '' ? undefined : apiKeyInput,
        audienceId: audienceIdInput,
        fromName: fromNameInput,
        fromEmail: fromEmailInput,
        replyToEmail: replyToEmailInput,
      });
      setSettings(updated);
      setApiKeyInput('');
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
        <span className="mb-1 block text-zinc-600">API key</span>
        <input
          type="password"
          value={apiKeyInput}
          onChange={(e) => setApiKeyInput(e.target.value)}
          placeholder={settings.apiKeySet ? `Currently set: ${settings.apiKeyMasked} — leave blank to keep it` : 'e.g. abc123def456...-us21'}
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Found under Mailchimp → Account → Extras → API keys. Keep the &ldquo;-us21&rdquo;-style suffix — that&apos;s
          which Mailchimp datacenter your account lives on, not part of the key itself but required alongside it.
        </span>
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Audience (List) ID</span>
        <input
          value={audienceIdInput}
          onChange={(e) => setAudienceIdInput(e.target.value)}
          placeholder="e.g. a1b2c3d4e5"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Found under Audience → Settings → Audience name and defaults.
        </span>
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">From name</span>
        <input
          value={fromNameInput}
          onChange={(e) => setFromNameInput(e.target.value)}
          placeholder="e.g. Anna from AK.LUX.NAILS"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">From email</span>
        <input
          type="email"
          value={fromEmailInput}
          onChange={(e) => setFromEmailInput(e.target.value)}
          placeholder="e.g. lucy@akluxnails.com"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Must be on a domain verified &amp; authenticated in this Mailchimp account (Account → Domains) — otherwise
          Mailchimp silently sends from the account&apos;s own default address instead, which can be a different
          business entirely if the Mailchimp account is shared.
        </span>
      </label>
      <label className="text-sm">
        <span className="mb-1 block text-zinc-600">Reply-to email</span>
        <input
          type="email"
          value={replyToEmailInput}
          onChange={(e) => setReplyToEmailInput(e.target.value)}
          placeholder="e.g. hello@akluxnails.com"
          autoComplete="off"
          className="w-full rounded border border-zinc-300 px-2 py-1.5"
        />
        <span className="mt-1 block text-xs text-zinc-400">
          Where a customer&apos;s reply to a win-back email actually lands — Mailchimp requires this on every send.
        </span>
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
        {settings.configured ? (
          <span className="text-xs text-green-700">Configured — win-back emails can send.</span>
        ) : (
          <span className="text-xs text-zinc-400">Not fully configured yet — win-back emails stay off.</span>
        )}
      </div>

      <p className="text-xs text-zinc-400">
        Last updated {settings.updatedAt ? new Date(settings.updatedAt).toLocaleString() : 'never'}
        {settings.updatedBy ? ` by ${settings.updatedBy}` : ''}.
      </p>
    </form>
  );
}
