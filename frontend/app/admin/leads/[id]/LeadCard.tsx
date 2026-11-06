'use client';

import Link from 'next/link';
import { useRef, useState } from 'react';
import { t } from '../../../lib/i18n';
import FunnelPanel, { stoppedLine, whyText } from '../FunnelPanel';
import type { Language, LeadDetail, LeadFunnel, LeadStatus } from '../../../lib/types';

const STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'BOOKED', 'LOST', 'SPAM'];
function apptTone(status: string): 'ok' | 'us' | 'bad' | 'off' {
  if (status === 'ACCEPTED') return 'ok';
  if (status === 'CANCELLED_BY_SELLER') return 'us';
  if (status === 'CANCELLED_BY_CUSTOMER' || status === 'DECLINED' || status === 'NO_SHOW') return 'bad';
  return 'off';
}
const APPT_PILL = {
  ok: 'text-green-700',
  us: 'rounded-full bg-red-600 px-2 py-0.5 text-xs font-semibold text-white',
  bad: 'rounded-full bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-800',
  off: 'text-zinc-500',
};
const APPT_STATUSES = ['ACCEPTED', 'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_SELLER', 'NO_SHOW', 'DECLINED'];
const LOST_REASONS = ['PRICE', 'NO_ANSWER', 'NOT_INTERESTED', 'BOUGHT_ELSEWHERE', 'NOT_ELIGIBLE', 'TOO_FAR', 'WRONG_NUMBER_SPAM', 'TIMING', 'OTHER'];
const SOURCES = ['Instagram', 'Yelp', 'Google', 'Facebook', 'TikTok', 'Referral', 'Walk-in', 'Phone', 'Other', 'Unknown'];
const field = 'rounded border border-zinc-300 px-2 py-1.5 text-sm focus:border-zinc-500 focus:outline-none';
const primary = 'rounded bg-zinc-900 px-3 py-1.5 text-sm text-white disabled:opacity-40';
const link = 'min-w-0 rounded px-3 py-1.5 text-sm ring-1 ring-zinc-300 hover:bg-zinc-50';

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

function Details({ payload, language, open }: { payload: Record<string, string>; language: Language | null; open?: boolean }) {
  const entries = Object.entries(payload).filter(([, v]) => !/^https:\/\//i.test(v) || !/record|audio|mp3/i.test(v));
  if (entries.length === 0) return null;
  return (
    <details open={open} className="mt-1 text-xs text-zinc-600">
      <summary className="cursor-pointer">{t(language, 'leadDetails')}</summary>
      <dl className="mt-1 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-0.5">
        {entries.map(([k, v]) => (<div key={k} className="contents"><dt className="text-zinc-500">{k}</dt><dd className="break-all">{v}</dd></div>))}
      </dl>
    </details>
  );
}

type Kind = 'contact' | 'sms' | 'email' | 'visit' | 'note' | 'system';
type Item = {
  key: string; at: string; kind: Kind; label: string; text?: string | null; by?: string | null;
  touchId?: number; payload?: Record<string, string> | null; inbound?: boolean; emailId?: number;
  opened?: boolean; clicked?: boolean; hasContent?: boolean; tone?: string; future?: boolean; openDetails?: boolean;
};
type PanelTab = 'timeline' | 'notes' | 'visits' | 'emails' | 'web';
type Filter = 'all' | Kind;
const FILTERS: { id: Filter; key: 'crmFilterAll' | 'crmFilterContacts' | 'crmFilterMessages' | 'crmFilterEmails' | 'crmFilterVisits' | 'crmFilterNotes' | 'crmFilterSystem' }[] = [
  { id: 'all', key: 'crmFilterAll' }, { id: 'contact', key: 'crmFilterContacts' }, { id: 'sms', key: 'crmFilterMessages' },
  { id: 'email', key: 'crmFilterEmails' }, { id: 'visit', key: 'crmFilterVisits' }, { id: 'note', key: 'crmFilterNotes' }, { id: 'system', key: 'crmFilterSystem' },
];
const DOT: Record<Kind, string> = {
  contact: 'bg-blue-500', sms: 'bg-emerald-500', email: 'bg-violet-500', visit: 'bg-teal-600', note: 'bg-amber-500', system: 'bg-zinc-400',
};
const PAGE = 25;

function dayKey(iso: string): string {
  const d = new Date(iso);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

function dayTitle(iso: string, lang: Language | null, now: number): string {
  const d = new Date(iso);
  const n = new Date(now);
  if (dayKey(iso) === dayKey(n.toISOString())) return t(lang, 'crmToday');
  const y = new Date(now - 86_400_000);
  if (dayKey(iso) === dayKey(y.toISOString())) return t(lang, 'crmYesterday');
  return d.toLocaleDateString(lang === 'RU' ? 'ru-RU' : 'en-US', { weekday: 'short', month: 'short', day: 'numeric', year: d.getFullYear() === n.getFullYear() ? undefined : 'numeric' });
}

function timeOnly(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleTimeString(lang === 'RU' ? 'ru-RU' : 'en-US', { hour: 'numeric', minute: '2-digit' });
}

export default function LeadCard({ initial, language = null, stopFunnel = null }: { initial: LeadDetail; language?: Language | null; stopFunnel?: string | null }) {
  const [data, setData] = useState(initial);
  const [losing, setLosing] = useState(false);
  const [lostReason, setLostReason] = useState('');
  const [lostComment, setLostComment] = useState('');
  const { lead } = data;
  const [callback, setCallback] = useState(toLocalInput(lead.callbackAt));
  const [note, setNote] = useState('');
  const [mailId, setMailId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<PanelTab>('timeline');
  const [filter, setFilter] = useState<Filter>('all');
  const [shown, setShown] = useState(PAGE);
  const [now] = useState(() => Date.now());
  const [funnelList, setFunnelList] = useState<LeadFunnel[]>([]);
  const noteRef = useRef<HTMLTextAreaElement>(null);

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

  async function addNote(e?: React.FormEvent) {
    e?.preventDefault();
    if (!note.trim()) return;
    if (await send(`/api/leads/${lead.id}/events`, 'POST', { type: 'NOTE', text: note.trim() })) setNote('');
  }

  function openNotes() {
    setTab('notes');
    setTimeout(() => noteRef.current?.focus(), 50);
  }

  const money = (n: number) => `$${n.toLocaleString(language === 'RU' ? 'ru-RU' : 'en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
  const notes = data.events.filter((x) => x.type === 'NOTE').sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  const sp = data.spend;

  const sourceLabel = (s: string) =>
    ['Unknown', 'Referral', 'Walk-in', 'Phone', 'Other'].includes(s)
      ? t(language, `leadSrc_${s.replace('-', '_')}` as 'leadSrc_Other')
      : s;

  const title = lead.name ?? lead.phoneNumber ?? lead.email ?? '';
  const chat = lead.phoneNumber ? `/admin/messages?phone=${encodeURIComponent(lead.phoneNumber)}` : null;
  const overdue = lead.callbackAt != null && new Date(lead.callbackAt).getTime() <= now && (lead.status === 'NEW' || lead.status === 'CONTACTED');

  const items: Item[] = [
    ...data.forms.map((f): Item => {
      const { Form: kindName, ...fields } = f.fields;
      return { key: `f${f.key}`, at: f.at, kind: 'contact', label: `${t(language, 'crmFormEvent')}: ${kindName ?? ''}`, payload: fields, openDetails: true };
    }),
    ...data.touches.filter((x) => !(x.channel === 'Academy' && data.forms.some((f) => f.kind === 'academy' && Math.abs(new Date(f.at).getTime() - new Date(x.createdAt).getTime()) < 10 * 60000))).map((x): Item => ({
      key: `t${x.id}`, touchId: x.id, at: x.createdAt, kind: 'contact', label: `${t(language, 'leadEventTouch')}: ${x.channel}`,
      text: [x.source, x.detail].filter(Boolean).join(' · '), payload: x.payload,
    })),
    ...data.events.map((x): Item => {
      const manual = x.type === 'NOTE' || x.type === 'CALL' || x.type === 'AGREEMENT';
      const label = manual ? t(language, `leadEntry${x.type}` as 'leadEntryNOTE') : t(language, `leadEvent${x.type}` as 'leadEventSTATUS');
      let text = x.text;
      if (x.type === 'STATUS' && x.text) text = t(language, `leadStatus${x.text}` as 'leadStatusNEW');
      if (x.type === 'LOST_REASON' && x.text) {
        const [code, ...rest] = x.text.split(': ');
        text = LOST_REASONS.includes(code) ? [t(language, `leadLost_${code}` as 'leadLost_PRICE'), ...rest].join(': ') : x.text;
      }
      if (x.type === 'CALLBACK' && x.text) text = fmt(x.text, language);
      return { key: `e${x.id}`, at: x.createdAt, kind: manual ? 'note' : 'system', label, text, by: x.createdBy };
    }),
    ...data.sms.map((m): Item => ({
      key: `s${m.id}`, at: m.at, kind: 'sms', inbound: m.direction === 'INBOUND', label: t(language, m.direction === 'INBOUND' ? 'crmSmsIn' : 'crmSmsOut'), text: m.body,
    })),
    ...data.emails.map((m): Item => ({
      key: `m${m.id}`, at: m.sentAt, kind: 'email', label: t(language, 'crmEmailSentEvent'), text: m.kind, emailId: m.id,
      opened: m.openedAt != null, clicked: m.clickedAt != null, hasContent: m.hasContent,
    })),
    ...data.appointments.flatMap((a) => (a.startAt ? [{ ...a, startAt: a.startAt }] : [])).map((a): Item => ({
      key: `a${a.id}`, at: a.startAt, kind: 'visit', label: `${t(language, 'crmApptEvent')}: ${APPT_STATUSES.includes(a.status) ? t(language, `leadAppt_${a.status}` as 'leadAppt_ACCEPTED') : a.status}`,
      text: [a.services, a.technician ? `${t(language, 'crmWith')} ${a.technician}` : null, a.paid != null && a.paid > 0 ? `${t(language, 'crmPaid')} ${money(a.paid)}` : null].filter(Boolean).join(' · '),
      future: new Date(a.startAt).getTime() > now, tone: apptTone(a.status),
    })),
    ...(data.payments ?? []).map((p): Item => ({
      key: `p${p.id}`, at: p.at, kind: 'visit', label: t(language, 'crmPaymentEvent'),
      text: [money(p.amount), p.tip > 0 ? `+ ${money(p.tip)} ${t(language, 'crmTips')}` : null, p.description].filter(Boolean).join(' · '),
    })),
    ...funnelList.flatMap((f) => [
      ...f.steps.filter((s) => s.at && s.state !== 'PENDING' && s.state !== 'NOT_SENT').flatMap((s): Item[] => {
        const at = s.at as string;
        const near = (iso: string) => Math.abs(new Date(iso).getTime() - new Date(at).getTime()) < 10 * 60 * 1000;
        const dup = s.state === 'DONE' && (s.channel === 'sms' ? data.sms.some((m) => m.direction === 'OUTBOUND' && near(m.at)) : data.emails.some((m) => near(m.sentAt)));
        if (dup) return [];
        const why = whyText(s.detail, language);
        return [{
          key: `f${f.key}${s.key}`, at, kind: s.state === 'DONE' ? (s.channel === 'sms' ? 'sms' : 'email') : 'system',
          label: `${t(language, 'funnelTimeline')}: ${t(language, `funnelStep_${s.key}` as 'funnelStep_sms')}`,
          text: `${t(language, `funnelStep${s.state}` as 'funnelStepDONE')}${why && s.state !== 'DONE' ? `: ${why}` : ''}`,
        }];
      }),
      ...(f.stoppedAt && f.state === 'STOPPED' ? [{
        key: `fs${f.key}`, at: f.stoppedAt, kind: 'system' as Kind, label: t(language, 'funnelTimelineStopped'),
        text: [stoppedLine(f, language), f.stopNote].filter(Boolean).join('. '),
      }] : []),
    ]),
  ].sort((a, b) => (a.at < b.at ? 1 : -1));

  const counts = Object.fromEntries(FILTERS.map((f) => [f.id, f.id === 'all' ? items.length : items.filter((i) => i.kind === f.id).length])) as Record<Filter, number>;
  const filtered = items.filter((i) => filter === 'all' || i.kind === filter);
  const visible = filtered.slice(0, shown);

  const tabs: { id: PanelTab; label: string; n?: number }[] = [
    { id: 'timeline', label: t(language, 'crmTabTimeline'), n: items.length },
    { id: 'notes', label: t(language, 'crmTabNotes'), n: notes.length + (data.squareNote ? 1 : 0) },
    ...(lead.squareCustomerId ? [{ id: 'visits' as const, label: t(language, 'crmTabVisits'), n: data.appointments.length }] : []),
    { id: 'emails', label: t(language, 'crmTabEmails'), n: data.emails.length },
    { id: 'web', label: t(language, 'crmTabWeb'), n: data.origins.length },
  ];

  const squareNoteCard = data.squareNote && (
    <div className="rounded-lg bg-sky-50 px-3 py-2 ring-1 ring-sky-200">
      <p className="whitespace-pre-wrap break-words text-sm text-zinc-900">{data.squareNote}</p>
      <p className="mt-1 text-xs text-zinc-500">{t(language, 'crmSquareNote')}</p>
    </div>
  );

  return (
    <div className="flex min-w-0 max-w-full flex-col gap-4">
      <Link href="/admin/leads" className="text-sm text-zinc-600 underline">{t(language, 'leadsBack')}</Link>

      <section className="rounded-lg p-4 ring-1 ring-zinc-200">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-2">
          <h2 className="mr-1 break-words text-lg font-semibold">{title}</h2>
          {lead.sourceEditable ? (
            <select
              aria-label={t(language, 'leadSourcePick')}
              title={t(language, 'leadSourceHint')}
              value={lead.source}
              disabled={busy}
              onChange={(e) => send(`/api/leads/${lead.id}`, 'PATCH', { source: e.target.value })}
              className={`rounded px-2 py-1 text-xs ${lead.source === 'Unknown' ? 'bg-amber-100 text-amber-900' : 'bg-zinc-100 text-zinc-700'}`}
            >
              {!SOURCES.includes(lead.source) && <option value={lead.source}>{lead.source}</option>}
              {SOURCES.map((s) => <option key={s} value={s}>{sourceLabel(s)}</option>)}
            </select>
          ) : (
            <span className="rounded bg-zinc-100 px-2 py-1 text-xs text-zinc-700" title={t(language, 'leadSourceLocked')}>{lead.source}</span>
          )}
          {lead.academy && <span className="rounded bg-violet-100 px-2 py-1 text-xs text-violet-800">{t(language, 'crmAcademy')}</span>}
          {lead.consultState === 'UPCOMING' && lead.consultAt && <span className="rounded bg-sky-100 px-2 py-1 text-xs text-sky-800">{t(language, 'crmConsultUpcoming')} {fmt(lead.consultAt, language)}</span>}
          {lead.consultState === 'DONE' && lead.consultAt && <span className="rounded bg-orange-100 px-2 py-1 text-xs text-orange-800">{t(language, 'crmConsultDone')} {fmt(lead.consultAt, language)}</span>}
          {overdue && <span className="rounded bg-amber-100 px-2 py-1 text-xs text-amber-800">{t(language, 'leadReminderDue')}</span>}
        </div>
        <div className="mt-2 flex flex-col gap-0.5 text-sm text-zinc-700">
          {lead.phoneNumber && <div>{lead.phoneNumber}</div>}
          {lead.email && <div className="break-all">{lead.email}</div>}
          <div className="text-xs text-zinc-500">{fmt(lead.createdAt, language)} · {lead.touches} {t(language, 'leadTouches')}</div>
        </div>
        <div className="mt-3 grid grid-cols-[repeat(2,minmax(0,1fr))] gap-2 sm:flex sm:flex-wrap">
          {lead.phoneNumber && <a href={`tel:${lead.phoneNumber}`} className={`${link} py-2.5 text-center`}>{t(language, 'crmCall')}</a>}
          {chat && <Link href={chat} className={`${link} py-2.5 text-center`}>{t(language, 'leadOpenChat')}</Link>}
          <button className={`${link} py-2.5`} onClick={openNotes}>{t(language, 'crmNoteSave')}</button>
          {lead.squareUrl
            ? <a href={lead.squareUrl} target="_blank" rel="noreferrer" className={`${link} py-2.5 text-center`}>{t(language, 'leadOpenSquare')}</a>
            : <span className="px-1 py-2.5 text-center text-sm text-zinc-500">{t(language, 'leadNoSquare')}</span>}
        </div>
      </section>

      <section className="flex min-w-0 flex-wrap items-center gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
        <select
          aria-label="status"
          value={lead.status}
          disabled={busy}
          onChange={(e) => (e.target.value === 'LOST' ? setLosing(true) : send(`/api/leads/${lead.id}`, 'PATCH', { status: e.target.value }))}
          className={`${field} py-2`}
        >
          {STATUSES.map((s) => <option key={s} value={s}>{t(language, `leadStatus${s}` as 'leadStatusNEW')}</option>)}
        </select>
        {lead.status === 'LOST' && lead.lostReason && (
          <span className="rounded bg-red-50 px-2 py-0.5 text-xs text-red-800">
            {LOST_REASONS.includes(lead.lostReason) ? t(language, `leadLost_${lead.lostReason}` as 'leadLost_PRICE') : lead.lostReason}
            {lead.lostComment ? `: ${lead.lostComment}` : ''}
          </span>
        )}
        {losing && (
          <div className="flex w-full flex-wrap items-center gap-2 rounded bg-red-50 p-3">
            <select aria-label="lost reason" value={lostReason} onChange={(e) => setLostReason(e.target.value)} className={field}>
              <option value="">{t(language, 'leadLostChoose')}</option>
              {LOST_REASONS.map((r) => <option key={r} value={r}>{t(language, `leadLost_${r}` as 'leadLost_PRICE')}</option>)}
            </select>
            <input
              value={lostComment}
              onChange={(e) => setLostComment(e.target.value)}
              placeholder={t(language, 'leadLostComment')}
              className={`${field} min-w-0 flex-1`}
            />
            <button
              className={primary}
              disabled={busy || !lostReason || (lostReason === 'OTHER' && !lostComment.trim())}
              onClick={async () => {
                if (await send(`/api/leads/${lead.id}`, 'PATCH', { status: 'LOST', lostReason, lostComment })) { setLosing(false); setLostReason(''); setLostComment(''); }
              }}
            >
              {t(language, 'leadLostConfirm')}
            </button>
            <button className={link} onClick={() => { setLosing(false); setLostReason(''); setLostComment(''); }}>{t(language, 'leadLostCancel')}</button>
          </div>
        )}
        <div className="flex w-full min-w-0 flex-wrap items-center gap-2 text-sm text-zinc-600 sm:w-auto">
          <label htmlFor="lead-callback" className="w-full sm:w-auto">{t(language, 'leadCallback')}</label>
          <input id="lead-callback" type="datetime-local" value={callback} onChange={(e) => setCallback(e.target.value)} className={`${field} w-full min-w-0 sm:w-auto sm:flex-none`} />
          <button className={primary} disabled={busy || !callback} onClick={() => send(`/api/leads/${lead.id}`, 'PATCH', { callbackAt: new Date(callback).toISOString() })}>
            {t(language, 'leadCallbackSet')}
          </button>
          {lead.callbackAt && (
            <button className="text-sm text-zinc-600 underline" disabled={busy} onClick={() => { setCallback(''); send(`/api/leads/${lead.id}`, 'PATCH', { clearCallback: true }); }}>
              {t(language, 'leadCallbackClear')}
            </button>
          )}
        </div>
        {(lead.status === 'NEW' || lead.status === 'CONTACTED') && (
          <div className="flex w-full flex-wrap items-center gap-2">
            {lead.closeReady ? (
              <>
                <p className="w-full text-sm text-red-800">{lead.attempts} {t(language, 'crmAttempts')}. {t(language, 'crmCloseHint')}</p>
                <button
                  className="rounded bg-red-600 px-3 py-2 text-sm text-white disabled:opacity-40"
                  disabled={busy}
                  onClick={() => send(`/api/leads/${lead.id}`, 'PATCH', { status: 'LOST', lostReason: 'NO_ANSWER', lostComment: `${lead.attempts} ${t(language, 'crmAttempts')}` })}
                >
                  {t(language, 'crmCloseNoAnswer')}
                </button>
              </>
            ) : null}
            <button className={`${link} py-2`} disabled={busy} onClick={() => send(`/api/leads/${lead.id}/attempts`, 'POST', {})}>{t(language, 'crmNoAnswer')}</button>
            {lead.attempts > 0 && <span className="text-sm text-zinc-600">{lead.attempts}/3 {t(language, 'crmAttempts')}</span>}
          </div>
        )}
        {error && <p className="w-full text-sm text-red-600">{error}</p>}
      </section>

      <FunnelPanel leadId={lead.id} language={language} onChanged={reload} onLoaded={setFunnelList} autoStop={stopFunnel} />

      <nav aria-label="sections" className="sticky top-0 z-10 -mx-1 overflow-x-auto bg-white/95 px-1 py-1 backdrop-blur">
        <div className="flex gap-1">
          {tabs.map((x) => (
            <button
              key={x.id}
              onClick={() => setTab(x.id)}
              aria-current={tab === x.id}
              className={`flex shrink-0 items-center gap-1.5 rounded-full px-3.5 py-2 text-sm ${tab === x.id ? 'bg-zinc-900 text-white' : 'text-zinc-700 hover:bg-zinc-100'}`}
            >
              {x.label}
              {x.n != null && x.n > 0 && <span className={`rounded-full px-1.5 text-xs tabular-nums ${tab === x.id ? 'bg-white/20' : 'bg-zinc-100 text-zinc-600'}`}>{x.n}</span>}
            </button>
          ))}
        </div>
      </nav>

      {tab === 'timeline' && (
        <section className="flex flex-col gap-3">
          <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1">
            {FILTERS.filter((f) => f.id === 'all' || counts[f.id] > 0).map((f) => (
              <button
                key={f.id}
                onClick={() => { setFilter(f.id); setShown(PAGE); }}
                aria-pressed={filter === f.id}
                className={`shrink-0 rounded-full px-3 py-1 text-xs ring-1 ${filter === f.id ? 'bg-zinc-800 text-white ring-zinc-800' : 'text-zinc-600 ring-zinc-300'}`}
              >
                {t(language, f.key)} {counts[f.id]}
              </button>
            ))}
          </div>
          {filtered.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'crmNothingHere')}</p>}
          <ol className="flex flex-col">
            {visible.map((r, idx) => {
              const newDay = idx === 0 || dayKey(visible[idx - 1].at) !== dayKey(r.at);
              return (
                <li key={r.key} className="min-w-0">
                  {newDay && <div className="sticky top-12 z-[1] bg-white/95 py-1.5 text-xs font-semibold uppercase tracking-wide text-zinc-500">{dayTitle(r.at, language, now)}</div>}
                  <div className="flex gap-3">
                    <div className="flex flex-col items-center">
                      <span className={`mt-2 h-2.5 w-2.5 shrink-0 rounded-full ${r.tone === 'us' || r.tone === 'bad' ? 'bg-red-600 ring-2 ring-red-200' : DOT[r.kind]}`} />
                      <span className="w-px flex-1 bg-zinc-200" />
                    </div>
                    <div className="min-w-0 flex-1 pb-4">
                      <div className="flex flex-wrap items-center gap-x-2 text-xs text-zinc-500">
                        <span className={r.kind === 'note' ? 'font-semibold text-zinc-800' : r.tone === 'us' ? 'rounded bg-red-600 px-1.5 py-0.5 font-semibold text-white' : r.tone === 'bad' ? 'rounded bg-red-100 px-1.5 py-0.5 font-semibold text-red-800' : 'text-zinc-700'}>{r.label}</span>
                        <span className="tabular-nums">{timeOnly(r.at, language)}</span>
                        {r.by && <span>{r.by}</span>}
                        {r.future && <span className="rounded bg-teal-100 px-1.5 text-teal-800">{t(language, 'crmNextVisit')}</span>}
                      </div>
                      {r.kind === 'sms' && r.text && (
                        <p className={`mt-1 inline-block max-w-full whitespace-pre-wrap break-words rounded-2xl px-3 py-2 text-sm ${r.inbound ? 'bg-emerald-50 text-zinc-900 ring-1 ring-emerald-200' : 'bg-zinc-100 text-zinc-800'}`}>{r.text}</p>
                      )}
                      {r.kind === 'note' && r.text && (
                        <p className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-amber-50 px-3 py-2 text-sm text-zinc-900 ring-1 ring-amber-200">{r.text}</p>
                      )}
                      {r.kind !== 'sms' && r.kind !== 'note' && r.text && <p className={`break-words text-sm ${r.tone === 'off' ? 'text-zinc-500' : r.tone === 'us' || r.tone === 'bad' ? 'text-red-800' : 'text-zinc-800'}`}>{r.text}</p>}
                      {r.kind === 'email' && (
                        <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs">
                          {r.opened && <span className="rounded bg-green-100 px-1.5 text-green-800">{t(language, 'crmEmailOpened')}</span>}
                          {r.clicked && <span className="rounded bg-blue-100 px-1.5 text-blue-800">{t(language, 'crmEmailClicked')}</span>}
                          {r.hasContent && <button className="underline" onClick={() => { setMailId(r.emailId ?? null); setTab('emails'); }}>{t(language, 'crmEmailView')}</button>}
                        </div>
                      )}
                      {r.touchId != null && recordingUrl(r.payload ?? null) && (
                        <audio controls preload="none" src={`/api/leads/recording/${r.touchId}`} className="mt-1 h-9 max-w-full" />
                      )}
                      {r.payload && <Details payload={r.payload} language={language} open={r.openDetails} />}
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
          {filtered.length > shown && (
            <button className={`${link} self-center`} onClick={() => setShown(shown + PAGE)}>{t(language, 'crmShowMore')} ({filtered.length - shown})</button>
          )}
        </section>
      )}

      {tab === 'notes' && (
        <section className="flex flex-col gap-3">
          <form onSubmit={addNote} className="flex flex-col gap-2">
            <textarea
              id="lead-note"
              ref={noteRef}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) addNote(); }}
              rows={3}
              placeholder={t(language, 'crmNotePlaceholder')}
              className={`${field} w-full resize-y text-base sm:text-sm`}
            />
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="hidden text-xs text-zinc-500 sm:inline">{t(language, 'crmNoteHint')}</span>
              <button type="submit" className={`${primary} w-full py-2.5 sm:w-auto`} disabled={busy || !note.trim()}>{t(language, 'crmNoteSave')}</button>
            </div>
          </form>
          {error && <p className="text-sm text-red-600">{error}</p>}
          {squareNoteCard}
          {data.squareNote && <p className="-mt-1 text-xs text-zinc-400">{t(language, 'crmSquareNoteHint')}</p>}
          {notes.length === 0 && !data.squareNote && <p className="text-sm text-zinc-500">{t(language, 'crmNotesEmpty')}</p>}
          <ul className="flex flex-col gap-2">
            {notes.map((n) => (
              <li key={n.id} className="rounded-lg bg-amber-50 px-3 py-2 ring-1 ring-amber-200">
                <p className="whitespace-pre-wrap break-words text-sm text-zinc-900">{n.text}</p>
                <p className="mt-1 text-xs text-zinc-500">{n.createdBy} · {fmt(n.createdAt, language)}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      {tab === 'visits' && lead.squareCustomerId && (
        <section className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {[
              [t(language, 'crmSpent'), money(sp.total), sp.tips > 0 ? `+ ${money(sp.tips)} ${t(language, 'crmTips')}` : ''],
              [t(language, 'crmVisits'), String(sp.visits), sp.firstVisit ? fmt(sp.firstVisit, language) : ''],
              [t(language, 'crmLastVisit'), sp.lastVisit ? fmt(sp.lastVisit, language) : '-', ''],
              [t(language, 'crmNextVisit'), sp.nextAppointment ? fmt(sp.nextAppointment, language) : '-', ''],
            ].map(([label, value, sub]) => (
              <div key={label} className="rounded-lg p-3 ring-1 ring-zinc-200">
                <div className="text-xs text-zinc-500">{label}</div>
                <div className="text-lg font-semibold tabular-nums">{value}</div>
                {sub && <div className="text-xs text-zinc-500">{sub}</div>}
              </div>
            ))}
          </div>
          {data.appointments.length === 0 && (data.payments ?? []).length === 0 && <p className="text-sm text-zinc-500">{t(language, 'leadAppointmentsEmpty')}</p>}
          <ul className="flex flex-col gap-1">
            {data.appointments.map((a) => (
              <li key={a.id} className={`flex flex-col gap-0.5 rounded-lg px-3 py-2 text-sm ring-1 ${apptTone(a.status) === 'us' ? 'bg-red-50 ring-red-400' : apptTone(a.status) === 'bad' ? 'bg-red-50/50 ring-red-200' : 'ring-zinc-200'}`}>
                <div className="flex flex-wrap items-baseline gap-x-3">
                  <span className="font-medium text-zinc-800">{a.startAt ? fmt(a.startAt, language) : ''}</span>
                  <span className={APPT_PILL[apptTone(a.status)]}>{apptTone(a.status) === 'us' || apptTone(a.status) === 'bad' ? '\u2715 ' : ''}{APPT_STATUSES.includes(a.status) ? t(language, `leadAppt_${a.status}` as 'leadAppt_ACCEPTED') : a.status}</span>
                  {a.paid != null && a.paid > 0 && <span className="ml-auto font-medium tabular-nums">{t(language, 'crmPaid')} {money(a.paid)}</span>}
                </div>
                {a.services && <div className="text-zinc-700">{a.services}</div>}
                {(a.technician || a.minutes) && (
                  <div className="text-xs text-zinc-500">
                    {a.technician ? `${t(language, 'crmWith')} ${a.technician}` : ''}{a.technician && a.minutes ? ' · ' : ''}{a.minutes ? `${a.minutes} ${language === 'RU' ? 'мин' : 'min'}` : ''}
                  </div>
                )}
              </li>
            ))}
            {(data.payments ?? []).map((p) => (
              <li key={p.id} className="flex flex-wrap items-baseline gap-x-3 rounded-lg bg-green-50/60 px-3 py-2 text-sm ring-1 ring-green-200">
                <span className="font-medium text-zinc-800">{fmt(p.at, language)}</span>
                <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">{t(language, 'crmPaymentEvent')}</span>
                {p.description && <span className="text-zinc-700">{p.description}</span>}
                <span className="ml-auto font-medium tabular-nums">{money(p.amount)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {tab === 'emails' && (
        <section className="flex flex-col gap-2">
          {data.emails.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'crmEmailsEmpty')}</p>}
          <ul className="flex flex-col gap-1">
            {data.emails.map((m) => (
              <li key={m.id} className="rounded-lg ring-1 ring-zinc-200">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
                  <span className="font-medium text-zinc-800">{m.kind}</span>
                  <span className="text-xs text-zinc-500">{fmt(m.sentAt, language)}</span>
                  {m.openedAt && <span className="rounded bg-green-100 px-1.5 text-xs text-green-800">{t(language, 'crmEmailOpened')}</span>}
                  {m.clickedAt && <span className="rounded bg-blue-100 px-1.5 text-xs text-blue-800">{t(language, 'crmEmailClicked')}</span>}
                  {m.hasContent
                    ? <button className="ml-auto py-1 text-xs underline" onClick={() => setMailId(mailId === m.id ? null : m.id)}>{mailId === m.id ? t(language, 'crmEmailClose') : t(language, 'crmEmailView')}</button>
                    : <span className="ml-auto text-xs text-zinc-400">{t(language, 'crmEmailNoContent')}</span>}
                </div>
                {mailId === m.id && (
                  <iframe title={m.kind} sandbox="" src={`/api/leads/${lead.id}/emails/${m.id}`} className="h-[28rem] w-full border-t border-zinc-200" />
                )}
              </li>
            ))}
          </ul>
          <p className="text-xs text-zinc-400">{t(language, 'crmEmailsNote')}</p>
        </section>
      )}

      {tab === 'web' && (
        <section className="flex flex-col gap-3">
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
      )}
    </div>
  );
}
