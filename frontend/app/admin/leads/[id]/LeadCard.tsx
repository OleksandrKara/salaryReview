'use client';

import Link from 'next/link';
import { useState } from 'react';
import { t } from '../../../lib/i18n';
import type { Language, LeadDetail, LeadStatus } from '../../../lib/types';

const STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'BOOKED', 'LOST'];
const APPT_STATUSES = ['ACCEPTED', 'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SELLER', 'NO_SHOW', 'DECLINED'];
const ENTRY_TYPES = ['NOTE', 'CALL', 'AGREEMENT'] as const;
const field = 'rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none';
const primary = 'rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-40';
const link = 'rounded px-3 py-1.5 text-sm ring-1 ring-zinc-300 hover:bg-zinc-50';

function fmt(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function recordingUrl(payload: Record<string, string> | null): string | null {
  if (!payload) return null;
  for (const [k, v] of Object.entries(payload)) {
    if (/record|audio|mp3/i.test(k) && /^https:\/\//i.test(v)) return v;
  }
  return null;
}

function Details({ payload, language }: { payload: Record<string, string>; language: Language | null }) {
  const entries = Object.entries(payload).filter(([, v]) => !/^https:\/\//i.test(v) || !/record|audio|mp3/i.test(v));
  if (entries.length === 0) return null;
  return (
    <details className="mt-1 text-xs text-zinc-600">
      <summary className="cursor-pointer">{t(language, 'leadDetails')}</summary>
      <dl className="mt-1 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5">
        {entries.map(([k, v]) => (<div key={k} className="contents"><dt className="text-zinc-500">{k}</dt><dd className="break-all">{v}</dd></div>))}
      </dl>
    </details>
  );
}

type Row = { key: string; at: string; label: string; text: string | null; by: string | null; tone: 'touch' | 'system' | 'manual'; payload?: Record<string, string> | null };

export default function LeadCard({ initial, language = null }: { initial: LeadDetail; language?: Language | null }) {
  const [data, setData] = useState(initial);
  const { lead } = data;
  const [callback, setCallback] = useState(toLocalInput(lead.callbackAt));
  const [entryType, setEntryType] = useState<(typeof ENTRY_TYPES)[number]>('NOTE');
  const [entry, setEntry] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function reload() {
    const res = await fetch(`/api/leads/${lead.id}`, { cache: 'no-store' });
    if (res.ok) setData(await res.json());
  }

  async function send(url: string, method: string, body: unknown) {
    setBusy(true);
    setError('');
    const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    setBusy(false);
    if (!res.ok) { setError(t(language, 'leadErr')); return false; }
    await reload();
    return true;
  }

  async function addEntry(e: React.FormEvent) {
    e.preventDefault();
    if (await send(`/api/leads/${lead.id}/events`, 'POST', { type: entryType, text: entry })) setEntry('');
  }

  const title = lead.name ?? lead.phoneNumber ?? lead.email ?? '';
  const chat = lead.phoneNumber ? `/admin/messages?phone=${encodeURIComponent(lead.phoneNumber)}` : null;
  const overdue = lead.callbackAt != null && new Date(lead.callbackAt) <= new Date() && (lead.status === 'NEW' || lead.status === 'CONTACTED');

  const rows: Row[] = [
    ...data.touches.map((x) => ({
      key: `t${x.id}`, at: x.createdAt, label: `${t(language, 'leadEventTouch')}: ${x.channel}`,
      text: [x.source, x.detail].filter(Boolean).join(' · '), by: null, tone: 'touch' as const, payload: x.payload,
    })),
    ...data.events.map((x) => {
      const manual = x.type === 'NOTE' || x.type === 'CALL' || x.type === 'AGREEMENT';
      const label = manual
        ? t(language, `leadEntry${x.type}` as 'leadEntryNOTE')
        : t(language, `leadEvent${x.type}` as 'leadEventSTATUS');
      let text = x.text;
      if (x.type === 'STATUS' && x.text) text = t(language, `leadStatus${x.text}` as 'leadStatusNEW');
      if (x.type === 'CALLBACK' && x.text) text = fmt(x.text, language);
      return { key: `e${x.id}`, at: x.createdAt, label, text, by: x.createdBy, tone: manual ? ('manual' as const) : ('system' as const) };
    }),
  ].sort((a, b) => (a.at < b.at ? 1 : -1));

  return (
    <div className="flex flex-col gap-6">
      <Link href="/admin/leads" className="text-sm text-zinc-600 underline">{t(language, 'leadsBack')}</Link>

      <section className="rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2 className="text-lg font-semibold">{title}</h2>
          <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs text-zinc-700">{lead.source}</span>
          {overdue && <span className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">{t(language, 'leadReminderDue')}</span>}
        </div>
        <dl className="mt-2 grid gap-x-6 gap-y-1 text-sm text-zinc-700 sm:grid-cols-2">
          {lead.phoneNumber && <div>{lead.phoneNumber}</div>}
          {lead.email && <div className="break-all">{lead.email}</div>}
          <div className="text-zinc-500">{fmt(lead.createdAt, language)} · {lead.touches} {t(language, 'leadTouches')}</div>
        </dl>
        <div className="mt-3 flex flex-wrap gap-2">
          {lead.squareUrl
            ? <a href={lead.squareUrl} target="_blank" rel="noreferrer" className={link}>{t(language, 'leadOpenSquare')}</a>
            : <span className="px-1 py-1.5 text-sm text-zinc-500">{t(language, 'leadNoSquare')}</span>}
          {chat && <Link href={chat} className={link}>{t(language, 'leadOpenChat')}</Link>}
        </div>
      </section>

      <section className="flex flex-wrap items-center gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
        <select
          aria-label="status"
          value={lead.status}
          disabled={busy}
          onChange={(e) => send(`/api/leads/${lead.id}`, 'PATCH', { status: e.target.value })}
          className={field}
        >
          {STATUSES.map((s) => <option key={s} value={s}>{t(language, `leadStatus${s}` as 'leadStatusNEW')}</option>)}
        </select>
        <label className="flex items-center gap-2 text-sm text-zinc-600">
          {t(language, 'leadCallback')}
          <input type="datetime-local" value={callback} onChange={(e) => setCallback(e.target.value)} className={field} />
        </label>
        <button className={primary} disabled={busy || !callback} onClick={() => send(`/api/leads/${lead.id}`, 'PATCH', { callbackAt: new Date(callback).toISOString() })}>
          {t(language, 'leadCallbackSet')}
        </button>
        {lead.callbackAt && (
          <button className="text-sm text-zinc-600 underline" disabled={busy} onClick={() => { setCallback(''); send(`/api/leads/${lead.id}`, 'PATCH', { clearCallback: true }); }}>
            {t(language, 'leadCallbackClear')}
          </button>
        )}
      </section>

      {lead.squareCustomerId && (
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">{t(language, 'leadAppointments')}</h3>
          {data.appointments.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'leadAppointmentsEmpty')}</p>}
          <ul className="flex flex-col gap-1">
            {data.appointments.map((a) => (
              <li key={a.id} className="flex flex-wrap items-baseline gap-x-3 text-sm ring-1 ring-zinc-200 rounded-lg px-3 py-2">
                <span className="font-medium text-zinc-800">{a.startAt ? fmt(a.startAt, language) : ''}</span>
                <span className="text-zinc-600">{APPT_STATUSES.includes(a.status) ? t(language, `leadAppt_${a.status}` as 'leadAppt_ACCEPTED') : a.status}</span>
                {a.services && <span className="text-zinc-500">{a.services}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="flex flex-col gap-3">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">{t(language, 'leadOrigins')}</h3>
        {data.origins.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'leadOriginsEmpty')}</p>}
        <ul className="flex flex-col gap-2">
          {data.origins.map((o, i) => {
            const utm = [o.utmSource, o.utmMedium, o.utmCampaign, o.utmTerm, o.utmContent].filter(Boolean).join(' / ');
            const typeKey = o.type === 'step1' || o.type === 'booking' ? o.type : 'other';
            const rows: [string, string | null][] = [
              [t(language, 'leadTraffic'), [o.trafficSource, utm].filter(Boolean).join(' · ') || null],
              [t(language, 'leadAdClick'), o.googleClick ? 'gclid (Google Ads)' : o.facebookClick ? 'fbclid (Meta)' : null],
              [t(language, 'leadLanding'), [o.landingPage, o.variant && `${t(language, 'leadVariant')} ${o.variant}`].filter(Boolean).join(' · ') || null],
              [t(language, 'leadService'), o.service],
              [t(language, 'leadReferrer'), o.referrer],
              [t(language, 'leadDevice'), [o.device, o.os, o.browser].filter(Boolean).join(' · ') || null],
              [t(language, 'leadLocation'), o.location ?? o.ip],
            ];
            return (
              <li key={i} className="rounded-lg p-3 text-sm ring-1 ring-zinc-200">
                <div className="mb-1 flex flex-wrap gap-x-2 text-xs text-zinc-500">
                  <span className="font-semibold text-zinc-800">{t(language, `leadOriginType_${typeKey}` as 'leadOriginType_other')}</span>
                  <span>{fmt(o.at, language)}</span>
                </div>
                <dl className="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5">
                  {rows.filter(([, v]) => v).map(([k, v]) => (<div key={k} className="contents"><dt className="text-zinc-500">{k}</dt><dd className="break-all text-zinc-800">{v}</dd></div>))}
                </dl>
              </li>
            );
          })}
        </ul>
      </section>

      <section className="flex flex-col gap-3">
        <h3 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">{t(language, 'leadHistory')}</h3>
        <form onSubmit={addEntry} className="flex flex-col gap-2 sm:flex-row">
          <select value={entryType} onChange={(e) => setEntryType(e.target.value as (typeof ENTRY_TYPES)[number])} className={field} aria-label="type">
            {ENTRY_TYPES.map((x) => <option key={x} value={x}>{t(language, `leadEntry${x}` as 'leadEntryNOTE')}</option>)}
          </select>
          <input value={entry} onChange={(e) => setEntry(e.target.value)} placeholder={t(language, 'leadEntryPlaceholder')} className={`${field} min-w-0 flex-1`} />
          <button type="submit" className={primary} disabled={busy || !entry.trim()}>{t(language, 'leadAddEntry')}</button>
        </form>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <ol className="flex flex-col">
          {rows.map((r) => (
            <li key={r.key} className="border-l-2 border-zinc-200 py-2 pl-4">
              <div className="flex flex-wrap items-center gap-x-2 text-xs text-zinc-500">
                <span className={r.tone === 'manual' ? 'font-semibold text-zinc-800' : ''}>{r.label}</span>
                <span>{fmt(r.at, language)}</span>
                {r.by && <span>{r.by}</span>}
              </div>
              {r.text && <p className="text-sm text-zinc-800">{r.text}</p>}
              {recordingUrl(r.payload ?? null) && (
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  <audio controls preload="none" src={recordingUrl(r.payload ?? null) ?? undefined} className="h-9 max-w-full" />
                  <a href={recordingUrl(r.payload ?? null) ?? undefined} target="_blank" rel="noreferrer" className="text-xs text-zinc-600 underline">{t(language, 'leadRecordingOpen')}</a>
                </div>
              )}
              {r.payload && <Details payload={r.payload} language={language} />}
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
