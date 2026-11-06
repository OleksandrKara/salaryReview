'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { FUNNEL_TONE } from './FunnelPanel';
import OutdatedPanel, { LockIcon } from './OutdatedPanel';
import { t } from '../../lib/i18n';
import type { Language, Lead, LeadFunnel } from '../../lib/types';

type Tab = 'ACTION' | 'CONSULT' | 'CONTACTED' | 'BOOKED' | 'LOST' | 'SPAM' | 'ALL';
const OUTDATED = 'OUTDATED' as const;
type AnyTab = Tab | typeof OUTDATED;
const TABS: Tab[] = ['ACTION', 'CONSULT', 'CONTACTED', 'BOOKED', 'LOST', 'SPAM', 'ALL'];
const DAY = 86_400_000;
const COLD_AFTER_DAYS = 7;
const STATUS_STYLE: Record<string, string> = {
  NEW: 'bg-blue-100 text-blue-800',
  CONTACTED: 'bg-zinc-100 text-zinc-700',
  BOOKED: 'bg-green-100 text-green-800',
  LOST: 'bg-zinc-100 text-zinc-500',
  SPAM: 'bg-zinc-100 text-zinc-400',
  OUTDATED: 'bg-zinc-100 text-zinc-500 ring-1 ring-inset ring-zinc-300',
};

function fmt(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

function span(ms: number, lang: Language | null): string {
  const ru = lang === 'RU';
  const min = Math.max(0, Math.floor(ms / 60_000));
  if (min < 60) return `${min} ${ru ? 'мин' : 'min'}`;
  const h = Math.floor(min / 60);
  if (h < 48) return `${h} ${ru ? 'ч' : 'h'}`;
  return `${Math.floor(h / 24)} ${ru ? 'дн' : 'd'}`;
}

const activity = (l: Lead) => new Date(l.activityAt ?? l.lastTouchAt).getTime();

function isActive(l: Lead): boolean {
  return l.status === 'NEW' || l.status === 'CONTACTED';
}

function isOverdue(l: Lead, now: number): boolean {
  return isActive(l) && l.callbackAt != null && new Date(l.callbackAt).getTime() <= now;
}

function needsAction(l: Lead, now: number): boolean {
  return isOverdue(l, now) || l.status === 'NEW';
}

type Section = { key: string; title: string; tone: string; leads: Lead[] };

function buildSections(leads: Lead[], tab: Tab, now: number, lang: Language | null): Section[] {
  const cb = (l: Lead) => (l.callbackAt ? new Date(l.callbackAt).getTime() : Infinity);
  const recent = (a: Lead, b: Lead) => activity(b) - activity(a);
  if (tab === 'ACTION') {
    const overdue = leads.filter((l) => isOverdue(l, now)).sort((a, b) => Number(b.closeReady) - Number(a.closeReady) || cb(a) - cb(b));
    const rest = leads.filter((l) => !isOverdue(l, now) && l.status === 'NEW');
    const fresh = rest.filter((l) => now - activity(l) <= COLD_AFTER_DAYS * DAY).sort(recent);
    const cold = rest.filter((l) => now - activity(l) > COLD_AFTER_DAYS * DAY).sort(recent);
    return [
      { key: 'overdue', title: t(lang, 'crmSecOverdue'), tone: 'text-red-700', leads: overdue },
      { key: 'new', title: t(lang, 'crmSecNew'), tone: 'text-blue-700', leads: fresh },
      { key: 'cold', title: t(lang, 'crmSecCold'), tone: 'text-zinc-500', leads: cold },
    ].filter((s) => s.leads.length > 0);
  }
  if (tab === 'CONSULT') {
    const consultAt = (l: Lead) => (l.consultAt ? new Date(l.consultAt).getTime() : 0);
    return [
      { key: 'stalled', title: t(lang, 'crmSecStalled'), tone: 'text-red-700', leads: leads.filter((l) => l.consultState === 'DONE').sort((a, b) => consultAt(b) - consultAt(a)) },
      { key: 'upcoming', title: t(lang, 'crmSecConsultUpcoming'), tone: 'text-blue-700', leads: leads.filter((l) => l.consultState === 'UPCOMING').sort((a, b) => consultAt(a) - consultAt(b)) },
    ].filter((s) => s.leads.length > 0);
  }
  if (tab === 'ALL') {
    return (['NEW', 'CONTACTED', 'BOOKED', 'LOST'] as const)
      .map((st) => ({
        key: st,
        title: st === 'CONTACTED' ? t(lang, 'crmSecProgress') : t(lang, `leadStatus${st}` as 'leadStatusNEW'),
        tone: st === 'NEW' ? 'text-blue-700' : 'text-zinc-500',
        leads: leads.filter((l) => l.status === st).sort(recent),
      }))
      .filter((s) => s.leads.length > 0);
  }
  const sorted = [...leads];
  if (tab === 'CONTACTED') sorted.sort((a, b) => cb(a) - cb(b) || recent(a, b));
  else sorted.sort(recent);
  return sorted.length ? [{ key: 'all', title: '', tone: '', leads: sorted }] : [];
}

export default function LeadsManager({ initialLeads, language = null, restricted = false }: { initialLeads: Lead[]; language?: Language | null; restricted?: boolean }) {
  const [leads, setLeads] = useState(initialLeads);
  const [tab, setTab] = useState<AnyTab | null>(null);
  const [outdatedTotal, setOutdatedTotal] = useState<number | null>(null);
  const [q, setQ] = useState('');
  const [source, setSource] = useState('');
  const [academyOnly, setAcademyOnly] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [now] = useState(() => Date.now());
  const [funnels, setFunnels] = useState<Record<number, LeadFunnel[]>>({});

  useEffect(() => {
    fetch('/api/leads/funnels', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : {})).then(setFunnels).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (restricted) return;
    fetch('/api/leads/outdated?page=0', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)).then((d) => { if (d) setOutdatedTotal(d.total); }).catch(() => undefined);
  }, [restricted]);

  const sources = useMemo(() => Array.from(new Set(leads.map((l) => l.source))).sort(), [leads]);
  const base = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return leads.filter((l) =>
      (!source || l.source === source) && (!academyOnly || l.academy) &&
      (!needle || [l.name, l.phoneNumber, l.email, l.source, l.lastNote].some((v) => v?.toLowerCase().includes(needle))));
  }, [leads, q, source, academyOnly]);
  const hasAcademy = useMemo(() => leads.some((l) => l.academy), [leads]);
  const hasConsult = useMemo(() => leads.some((l) => l.consultState), [leads]);

  const inTab = (l: Lead, x: Tab): boolean =>
    x === 'ALL' ? l.status !== 'SPAM' : x === 'CONSULT' ? isActive(l) && l.consultState != null : x === 'ACTION' ? (l.status === 'NEW' || isOverdue(l, now)) : l.status === x;
  const counts = useMemo(
    () => Object.fromEntries(TABS.map((x) => [x, base.filter((l) => inTab(l, x) && (x !== 'ACTION' || needsAction(l, now))).length])) as Record<Tab, number>,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base],
  );
  const activeTab: AnyTab = tab ?? (counts.ACTION > 0 ? 'ACTION' : 'ALL');
  const archive = activeTab === OUTDATED;
  const sections = archive ? [] : buildSections(base.filter((l) => inTab(l, activeTab as Tab)), activeTab as Tab, now, language);

  async function noAnswer(l: Lead) {
    setBusyId(l.id);
    const res = await fetch(`/api/leads/${l.id}/attempts`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    setBusyId(null);
    if (res.ok) {
      const next = new Date(Date.now() + 2 * DAY).toISOString();
      setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status: 'CONTACTED', callbackAt: next, attempts: x.attempts + 1, closeReady: x.attempts + 1 >= 3 } : x)));
    }
  }

  async function closeLost(l: Lead) {
    setBusyId(l.id);
    const res = await fetch(`/api/leads/${l.id}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'LOST', lostReason: 'NO_ANSWER', lostComment: `${l.attempts} ${t(language, 'crmAttempts')}` }),
    });
    setBusyId(null);
    if (res.ok) setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status: 'LOST', closeReady: false } : x)));
  }

  async function setStatus(l: Lead, status: 'CONTACTED' | 'SPAM') {
    setBusyId(l.id);
    const res = await fetch(`/api/leads/${l.id}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status }),
    });
    setBusyId(null);
    if (res.ok) setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status } : x)));
  }

  const tabLabel = (x: Tab) =>
    x === 'ACTION' ? t(language, 'crmTabAction')
      : x === 'CONSULT' ? t(language, 'crmTabConsult')
      : x === 'CONTACTED' ? t(language, 'crmSecProgress')
        : x === 'ALL' ? t(language, 'leadsFilterAll')
          : t(language, `leadStatus${x}` as 'leadStatusNEW');

  const quick = 'rounded px-2.5 py-1 text-xs ring-1 ring-zinc-300 hover:bg-zinc-100';

  return (
    <div className="flex flex-col gap-4">
      <div className={`flex flex-col gap-2 sm:flex-row ${archive ? 'hidden' : ''}`}>
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t(language, 'leadsSearch')}
          className="w-full rounded border border-zinc-300 px-3 py-2 text-sm focus:border-zinc-500 focus:outline-none"
        />
        <select
          aria-label={t(language, 'crmAllSources')}
          value={source}
          onChange={(e) => setSource(e.target.value)}
          className="rounded border border-zinc-300 px-2 py-2 text-sm sm:w-48"
        >
          <option value="">{t(language, 'crmAllSources')}</option>
          {sources.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>

      <div className="flex flex-wrap gap-2">
        {TABS.filter((x) => x !== 'CONSULT' || hasConsult).map((x) => (
          <button
            key={x}
            onClick={() => setTab(x)}
            className={`flex items-center gap-2 rounded-full px-3 py-1 text-sm ring-1 ${activeTab === x ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300 hover:bg-zinc-50'}`}
          >
            {tabLabel(x)}
            <span className={`rounded-full px-1.5 text-xs tabular-nums ${activeTab === x ? 'bg-white/20' : x === 'ACTION' && counts[x] > 0 ? 'bg-red-100 text-red-700' : 'bg-zinc-100 text-zinc-600'}`}>{counts[x]}</span>
          </button>
        ))}
        {!restricted && (
          <button
            onClick={() => setTab(OUTDATED)}
            aria-pressed={archive}
            className={`flex items-center gap-1.5 rounded-full border border-dashed px-3 py-1 text-sm ${archive ? 'border-zinc-700 bg-zinc-700 text-white' : 'border-zinc-400 bg-zinc-50 text-zinc-600 hover:bg-zinc-100'}`}
          >
            <LockIcon />
            {t(language, 'crmTabOutdated')}
            {outdatedTotal != null && <span className={`rounded-full px-1.5 text-xs tabular-nums ${archive ? 'bg-white/20' : 'bg-zinc-200 text-zinc-600'}`}>{outdatedTotal}</span>}
          </button>
        )}
        {hasAcademy && !archive && (
          <button
            onClick={() => setAcademyOnly(!academyOnly)}
            aria-pressed={academyOnly}
            className={`rounded-full px-3 py-1 text-sm ring-1 ${academyOnly ? 'bg-violet-700 text-white ring-violet-700' : 'text-violet-800 ring-violet-300 hover:bg-violet-50'}`}
          >
            {t(language, 'crmAcademyFilter')}
          </button>
        )}
      </div>

      {archive && <OutdatedPanel language={language} onTotal={setOutdatedTotal} />}

      {activeTab === 'ACTION' && (
        <p className="rounded-lg bg-zinc-50 px-3 py-2 text-xs text-zinc-600 ring-1 ring-zinc-200">{t(language, 'crmActionHint')}</p>
      )}
      {activeTab === 'ACTION' && counts.ACTION === 0 && (
        <p className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800 ring-1 ring-green-200">{t(language, 'crmAllDone')}</p>
      )}
      {sections.length === 0 && !archive && activeTab !== 'ACTION' && <p className="text-sm text-zinc-500">{t(language, 'leadsEmpty')}</p>}

      {sections.map((s) => (
        <section key={s.key} className="flex flex-col gap-2">
          {s.title && (
            <h3 className={`flex items-center gap-2 text-xs font-semibold uppercase tracking-wide ${s.tone}`}>
              {s.title}<span className="tabular-nums text-zinc-400">{s.leads.length}</span>
            </h3>
          )}
          <ul className="flex flex-col divide-y divide-zinc-200 rounded-lg ring-1 ring-zinc-200">
            {s.leads.map((l) => {
              const overdue = isOverdue(l, now);
              const waiting = l.status === 'NEW' ? span(now - activity(l), language) : null;
              const late = overdue && l.callbackAt ? span(now - new Date(l.callbackAt).getTime(), language) : null;
              const reason: { text: string; cls: string } | null =
                !isActive(l) ? null
                : l.closeReady ? { text: t(language, 'crmReasonClose'), cls: 'text-red-700' }
                : overdue ? { text: `${l.consultState === 'DONE' ? t(language, 'crmReasonConsult') + '. ' : ''}${t(language, 'crmReasonOverdue')} ${late}`, cls: 'text-red-700' }
                : l.status === 'NEW' ? { text: `${t(language, 'crmReasonNew')} ${waiting}`, cls: 'text-blue-700' }
                : null;
              const upcoming = !overdue && isActive(l) && l.callbackAt != null;
              return (
                <li key={l.id} className={`flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:gap-4 ${overdue ? 'bg-red-50/60' : ''}`}>
                  <Link href={`/admin/leads/${l.id}`} className="min-w-0 flex-1 hover:underline">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="truncate font-medium">{l.name ?? l.phoneNumber ?? l.email}</span>
                      <span className={`rounded px-1.5 py-0.5 text-xs ${STATUS_STYLE[l.status]}`}>{t(language, `leadStatus${l.status}` as 'leadStatusNEW')}</span>
                      {overdue && l.callbackAt && (
                        <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-800">{t(language, 'crmCallbackAt')} {fmt(l.callbackAt, language)}</span>
                      )}
                      {upcoming && l.callbackAt && (
                        <span className="rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800">{t(language, 'crmCallbackAt')} {fmt(l.callbackAt, language)}</span>
                      )}
                      {l.attempts > 0 && isActive(l) && (
                        <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-700">{l.attempts}/3 {t(language, 'crmAttempts')}</span>
                      )}
                      {l.academy && <span className="rounded bg-violet-100 px-1.5 py-0.5 text-xs text-violet-800">{t(language, 'crmAcademy')}</span>}
                      {l.consultState === 'UPCOMING' && l.consultAt && (
                        <span className="rounded bg-sky-100 px-1.5 py-0.5 text-xs text-sky-800">{t(language, 'crmConsultUpcoming')} {fmt(l.consultAt, language)}</span>
                      )}
                      {l.consultState === 'DONE' && l.consultAt && isActive(l) && (
                        <span className="rounded bg-orange-100 px-1.5 py-0.5 text-xs text-orange-800">{t(language, 'crmConsultDone')} {span(now - new Date(l.consultAt).getTime(), language)} {t(language, 'crmAgo')}</span>
                      )}
                      {(funnels[l.id] ?? []).filter((f) => f.state !== 'NOT_APPLIED' || f.canApply).map((f) => (
                        <span key={f.key} className={`rounded-full px-1.5 py-0.5 text-xs ${FUNNEL_TONE[f.state]}`} title={t(language, `funnelName_${f.key}` as 'funnelName_academy')}>
                          {t(language, 'funnelChip')}: {t(language, `funnelState_${f.state}` as 'funnelState_ACTIVE').toLowerCase()}
                        </span>
                      ))}
                      {waiting && <span className="text-xs text-zinc-500">{t(language, 'crmWaiting')} {waiting}</span>}
                    </div>
                    {reason && (
                      <div className={`mt-0.5 flex items-start gap-1.5 text-xs font-medium ${reason.cls}`}>
                        <span aria-hidden className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-current" />{reason.text}
                      </div>
                    )}
                    <div className="truncate text-xs text-zinc-500">
                      {l.name && l.phoneNumber ? `${l.phoneNumber} · ` : ''}{l.source} · {l.channel}
                      {l.touches > 1 ? ` · ${l.touches} ${t(language, 'leadTouches')}` : ''} · {fmt(new Date(activity(l)).toISOString(), language)}
                    </div>
                    {l.lastNote && (
                      <div className="mt-0.5 truncate text-xs text-zinc-700">
                        <span className="text-zinc-400">{l.lastNoteBy}: </span>{l.lastNote}
                      </div>
                    )}
                  </Link>
                  {isActive(l) && (
                    <div className="flex shrink-0 flex-wrap gap-2">
                      {l.phoneNumber && <a href={`tel:${l.phoneNumber}`} className={quick}>{t(language, 'crmCall')}</a>}
                      {l.phoneNumber && <Link href={`/admin/messages?phone=${encodeURIComponent(l.phoneNumber)}`} className={quick}>{t(language, 'crmText')}</Link>}
                      {l.closeReady ? (
                        <button className="rounded bg-red-600 px-2.5 py-1 text-xs text-white disabled:opacity-40" disabled={busyId === l.id} onClick={() => closeLost(l)}>{t(language, 'crmCloseNoAnswer')}</button>
                      ) : (
                        <button className={quick} disabled={busyId === l.id} onClick={() => noAnswer(l)}>{t(language, 'crmNoAnswer')}</button>
                      )}
                      <button className={quick} disabled={busyId === l.id} onClick={() => setStatus(l, 'SPAM')}>{t(language, 'crmMarkSpam')}</button>
                      {l.status === 'NEW' && (
                        <button className={quick} disabled={busyId === l.id} onClick={() => setStatus(l, 'CONTACTED')}>{t(language, 'crmMarkContacted')}</button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
