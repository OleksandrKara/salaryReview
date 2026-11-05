'use client';

import { useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, Lead } from '../../lib/types';

const STATUSES = ['NEW', 'CONTACTED', 'BOOKED', 'LOST'] as const;
const input = 'rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none';
const btn = 'rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-50';

function fmt(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function LeadCard({ lead, language, onChanged }: { lead: Lead; language: Language | null; onChanged: () => Promise<void> }) {
  const [callback, setCallback] = useState(toLocalInput(lead.callbackAt));
  const [notes, setNotes] = useState(lead.notes ?? '');
  const [sms, setSms] = useState('');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);

  async function patch(body: Record<string, unknown>) {
    setBusy(true);
    setMsg('');
    const res = await fetch(`/api/leads/${lead.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    setBusy(false);
    if (!res.ok) { setMsg(t(language, 'leadErr')); return; }
    await onChanged();
  }

  async function sendSms() {
    setBusy(true);
    setMsg('');
    const res = await fetch(`/api/leads/${lead.id}/sms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: sms }),
    });
    setBusy(false);
    if (!res.ok) { setMsg(t(language, 'leadErr')); return; }
    setSms('');
    setMsg(t(language, 'leadSmsSent'));
    await onChanged();
  }

  const overdue = lead.callbackAt != null && new Date(lead.callbackAt) <= new Date() && lead.status !== 'BOOKED' && lead.status !== 'LOST';

  return (
    <li data-testid="lead-row" className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-medium">{lead.name ?? lead.phoneNumber ?? lead.email}</span>
        {lead.name && lead.phoneNumber && <span className="text-sm text-zinc-600">{lead.phoneNumber}</span>}
        {lead.email && <span className="text-sm text-zinc-600">{lead.email}</span>}
        <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs text-zinc-700">{lead.source}</span>
        <span className="text-xs text-zinc-500">{lead.channel} · {fmt(lead.createdAt, language)}</span>
        {overdue && <span className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">{t(language, 'leadReminderDue')}</span>}
      </div>
      {lead.detail && <p className="mt-1 text-sm text-zinc-600">{lead.detail}</p>}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <select
          aria-label="status"
          value={lead.status}
          disabled={busy}
          onChange={(e) => patch({ status: e.target.value })}
          className={input}
        >
          {STATUSES.map((s) => <option key={s} value={s}>{t(language, `leadStatus${s}` as 'leadStatusNEW')}</option>)}
        </select>
        <label className="flex items-center gap-2 text-sm text-zinc-600">
          {t(language, 'leadCallback')}
          <input type="datetime-local" value={callback} onChange={(e) => setCallback(e.target.value)} className={input} />
        </label>
        <button
          className={btn}
          disabled={busy || !callback}
          onClick={() => patch({ callbackAt: new Date(callback).toISOString() })}
        >
          {t(language, 'leadCallbackSet')}
        </button>
        {lead.callbackAt && (
          <button className="text-sm text-zinc-600 underline" disabled={busy} onClick={() => { setCallback(''); patch({ clearCallback: true }); }}>
            {t(language, 'leadCallbackClear')}
          </button>
        )}
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input
          aria-label={t(language, 'leadNotes')}
          placeholder={t(language, 'leadNotes')}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          className={`${input} min-w-0 flex-1`}
        />
        <button className={btn} disabled={busy || notes === (lead.notes ?? '')} onClick={() => patch({ notes })}>
          {t(language, 'leadNotesSave')}
        </button>
      </div>

      {lead.phoneNumber && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            placeholder={t(language, 'leadSmsPlaceholder')}
            value={sms}
            onChange={(e) => setSms(e.target.value)}
            className={`${input} min-w-0 flex-1`}
          />
          <button className={btn} disabled={busy || !sms.trim()} onClick={sendSms}>{t(language, 'leadSmsSend')}</button>
        </div>
      )}
      {msg && <p className="mt-2 text-xs text-zinc-600">{msg}</p>}
    </li>
  );
}

export default function LeadsManager({ initialLeads, language = null }: { initialLeads: Lead[]; language?: Language | null }) {
  const [rows, setRows] = useState(initialLeads);
  const [filter, setFilter] = useState<string>('ALL');

  async function refresh() {
    const res = await fetch('/api/leads', { cache: 'no-store' });
    if (res.ok) setRows(await res.json());
  }

  const shown = filter === 'ALL' ? rows : rows.filter((r) => r.status === filter);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap gap-2">
        {['ALL', ...STATUSES].map((s) => (
          <button
            key={s}
            onClick={() => setFilter(s)}
            className={`rounded-full px-3 py-1 text-sm ring-1 ${filter === s ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300'}`}
          >
            {s === 'ALL' ? t(language, 'leadsFilterAll') : t(language, `leadStatus${s}` as 'leadStatusNEW')}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="text-sm text-zinc-500">{t(language, 'leadsEmpty')}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {shown.map((lead) => <LeadCard key={`${lead.id}-${lead.status}-${lead.callbackAt}-${lead.notes}`} lead={lead} language={language} onChanged={refresh} />)}
        </ul>
      )}
    </div>
  );
}
