'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { ProviderScheduleClosureAlertSettingsDto } from '../../../lib/types';

// Owner-editable "less than how many hours' notice counts as a closure" threshold for the
// provider_schedule_closure_alert Telegram alert (see ProviderScheduleClosureAlertConfigService).
// Unlike PromoTermsEditor, there's no external object to create on first save (no Square catalog
// entries) — this is a plain per-business number, so a single Save is enough, no confirm step.
export default function NoticeThresholdEditor({
  settings,
  onSaved,
}: {
  settings: ProviderScheduleClosureAlertSettingsDto;
  onSaved: (s: ProviderScheduleClosureAlertSettingsDto) => void;
}) {
  const [hours, setHours] = useState(String(settings.noticeThresholdHours));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const dirty = hours !== String(settings.noticeThresholdHours);

  async function save() {
    const parsed = Number(hours);
    if (!hours || !Number.isInteger(parsed) || parsed < 1 || parsed > 168) {
      setError('Enter a whole number of hours between 1 and 168 (one week)');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const updated = await api.updateProviderScheduleClosureAlertSettings(parsed);
      onSaved(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <div className="mb-1.5 text-sm font-medium text-zinc-700">Notice threshold</div>
      <p className="mb-2 text-xs text-zinc-500">
        Alert when a provider closes part of their own calendar with less than this many hours&apos; notice.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs text-zinc-500">
          <span className="mb-1 block">Hours</span>
          <input
            type="number"
            min="1"
            max="168"
            step="1"
            value={hours}
            onChange={(e) => {
              setHours(e.target.value);
              setError('');
            }}
            placeholder="e.g. 24"
            className="w-24 rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
          />
        </label>
        <button
          type="button"
          onClick={save}
          disabled={saving || !dirty}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
        >
          {saving && <Spinner className="h-3 w-3" />}
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
    </div>
  );
}
