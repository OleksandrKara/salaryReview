'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { ProviderScheduleClosureAlertSettingsDto, ProviderScheduleChangeHistoryDto } from '../../../lib/types';

// Independent notice/window policy, observation mode and recent evidence for availability alerts.
export default function NoticeThresholdEditor({
  settings,
  onSaved,
}: {
  settings: ProviderScheduleClosureAlertSettingsDto;
  onSaved: (s: ProviderScheduleClosureAlertSettingsDto) => void;
}) {
  const [hours, setHours] = useState(String(settings.noticeThresholdHours));
  const [lossHours, setLossHours] = useState(String((settings.minimumLossWindowMinutes ?? 240) / 60));
  const [observationOnly, setObservationOnly] = useState(settings.observationOnly ?? true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [history, setHistory] = useState<ProviderScheduleChangeHistoryDto | null>(null);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [historyError, setHistoryError] = useState('');

  const dirty = hours !== String(settings.noticeThresholdHours)
    || lossHours !== String((settings.minimumLossWindowMinutes ?? 240) / 60)
    || observationOnly !== (settings.observationOnly ?? true);

  async function save() {
    const parsed = Number(hours);
    if (!hours || !Number.isInteger(parsed) || parsed < 1 || parsed > 168) {
      setError('Enter a whole number of hours between 1 and 168 (one week)');
      return;
    }
    const minutes = Number(lossHours) * 60;
    if (!lossHours || !Number.isFinite(minutes) || minutes < 60 || minutes > 1440
      || Math.abs(minutes - Math.round(minutes)) > 0.000001) {
      setError('Enter a booking window between 1 and 24 hours, in whole minutes');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const updated = await api.updateProviderScheduleClosureAlertSettings({
        noticeThresholdHours: parsed, minimumLossWindowMinutes: Math.round(minutes), observationOnly,
      });
      setHours(String(updated.noticeThresholdHours));
      setLossHours(String(updated.minimumLossWindowMinutes / 60));
      setObservationOnly(updated.observationOnly);
      onSaved(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  async function loadHistory() {
    setLoadingHistory(true);
    setHistoryError('');
    try {
      setHistory(await api.getProviderScheduleChangeHistory());
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : 'Could not load recent changes');
    } finally {
      setLoadingHistory(false);
    }
  }

  function time(value: string, timezone: string) {
    return new Intl.DateTimeFormat('en-US', {
      timeZone: timezone, month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
    }).format(new Date(value));
  }

  function outcome(status: string, delivery: string | null) {
    if (delivery === 'SENT') return 'Telegram sent';
    if (delivery === 'FAILED') return 'Delivery failed';
    if (delivery === 'UNKNOWN' || delivery === 'ATTEMPTING') return 'Delivery unconfirmed';
    if (status === 'OBSERVED') return 'Recorded without Telegram';
    if (status === 'PENDING') return 'Awaiting repeat check';
    if (status === 'CONFIRMED') return 'Confirmed';
    if (status === 'RESOLVED') return 'Availability returned';
    if (status === 'EXPIRED') return 'Check expired';
    return 'Notification suppressed';
  }

  function reason(value: string) {
    const labels: Record<string, string> = {
      NATURAL_CUTOFF: 'Booking lead time reached',
      BOOKING_OVERLAP: 'Explained by customer appointments or booking limits',
      PROBE_CHANGED: 'Booking conditions or alert settings changed',
      STALE_BASELINE: 'Previous check was too old',
      SHORT_LOSS: 'Remaining loss is below the minimum window',
      INVALID_PROBE: 'Availability could not be checked',
      INVALID_RESPONSE: 'Calendar check was incomplete',
      INCOMPLETE_BOOKINGS: 'Customer appointments could not be fully checked',
    };
    return labels[value];
  }

  return (
    <div className="rounded-md bg-zinc-50 p-3">
      <div className="mb-1.5 text-sm font-medium text-zinc-700">Availability-change alerts</div>
      <p className="mb-2 text-xs text-zinc-500">
        Notify about a large continuous loss of online booking options, confirmed after at least 20 minutes.
        Customer appointments and minimum booking lead times are excluded.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs text-zinc-500">
          <span className="mb-1 block">Notice threshold (hours)</span>
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
        <label className="text-xs text-zinc-500">
          <span className="mb-1 block">Minimum lost booking window (hours)</span>
          <input type="number" min="1" max="24" step="0.5" value={lossHours}
            onChange={(e) => { setLossHours(e.target.value); setError(''); }}
            className="w-24 rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm" />
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
      <label className="mt-3 flex items-center gap-2 text-xs text-zinc-600">
        <input type="checkbox" checked={observationOnly} onChange={(e) => setObservationOnly(e.target.checked)} />
        Observation mode: record changes without sending Telegram
      </label>
      <p className="mt-2 text-xs text-zinc-500">
        Review recorded changes before enabling delivery. At most one message is sent per provider and affected date.
        A lost booking window does not identify who changed the calendar or measure closed working hours.
      </p>
      {error && <p className="mt-2 text-xs text-red-600">{error}</p>}
      <button type="button" onClick={loadHistory} disabled={loadingHistory}
        className="mt-3 text-xs font-medium text-zinc-700 underline disabled:opacity-40">
        {loadingHistory ? 'Loading…' : history ? 'Refresh recent changes' : 'View recent changes'}
      </button>
      {historyError && <p className="mt-2 text-xs text-red-600">{historyError}</p>}
      {history && (
        <div className="mt-3 space-y-2">
          {history.providers.some((provider) => provider.status === 'ERROR') && (
            <p className="text-xs text-amber-700">Some calendars could not be checked. Incomplete checks do not confirm changes.</p>
          )}
          {history.events.length === 0 && <p className="text-xs text-zinc-500">No large changes recorded yet.</p>}
          {history.events.slice(0, 10).map((event) => (
            <div key={event.id} className="rounded border border-zinc-200 bg-white p-2 text-xs">
              <div className="font-medium text-zinc-700">{event.providerName} · {outcome(event.status, event.deliveryStatus)}</div>
              <div className="mt-1 text-zinc-500">Previously available starts: {time(event.firstStart, event.timezone)} – {time(event.lastStart, event.timezone)}</div>
              <div className="mt-1 text-zinc-500">Detected: {time(event.detectedAt, event.timezone)}</div>
              {event.confirmedAt && <div className="mt-1 text-zinc-500">Confirmed: {time(event.confirmedAt, event.timezone)}</div>}
              {reason(event.reason) && <div className="mt-1 text-zinc-500">{reason(event.reason)}</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
