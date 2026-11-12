'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { FUNNEL_TONE } from './FunnelPanel';
import LeadActions from './LeadActions';
import { DAY, LAPSED_AFTER_DAYS, hasNextStep, isActive, isLapsed, isOverdue, lacksNextStep } from './leadActions';
import LeadIcons from './LeadIcons';
import ReminderSheet, { type ReachedOutBody } from './ReminderSheet';
import NeedsReplyPanel from './NeedsReplyPanel';
import OutdatedPanel, { LockIcon } from './OutdatedPanel';
import { t, tf } from '../../lib/i18n';
import type { Language, Lead, LeadFunnel, LeadListBadge, LeadOwnersView, NeedsReplyQueue } from '../../lib/types';

type Tab = 'ACTION' | 'CONSULT' | 'LAPSED' | 'CONTACTED' | 'BOOKED' | 'LOST' | 'SPAM' | 'ALL';
const OUTDATED = 'OUTDATED' as const;
const REPLY = 'REPLY' as const;
type AnyTab = Tab | typeof OUTDATED | typeof REPLY;
const TABS: Tab[] = ['ACTION', 'CONSULT', 'LAPSED', 'CONTACTED', 'BOOKED', 'LOST', 'SPAM', 'ALL'];
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

function needsAction(l: Lead, now: number): boolean {
  return isOverdue(l, now) || l.status === 'NEW';
}

type Section = { key: string; title: string; tone: string; leads: Lead[]; group?: 'todo' | 'inwork' };

function buildSections(leads: Lead[], tab: Tab, now: number, lang: Language | null): Section[] {
  const cb = (l: Lead) => (l.callbackAt ? new Date(l.callbackAt).getTime() : Infinity);
  const recent = (a: Lead, b: Lead) => activity(b) - activity(a);
  if (tab === 'ACTION') {
    const due = leads.filter((l) => isOverdue(l, now));
    const overdue = due.filter((l) => !isLapsed(l, now)).sort((a, b) => Number(b.closeReady) - Number(a.closeReady) || cb(a) - cb(b));
    const lapsed = due.filter((l) => isLapsed(l, now)).sort((a, b) => new Date(b.lastVisitAt ?? 0).getTime() - new Date(a.lastVisitAt ?? 0).getTime());
    const rest = leads.filter((l) => !isOverdue(l, now) && l.status === 'NEW');
    const fresh = rest.filter((l) => now - activity(l) <= COLD_AFTER_DAYS * DAY).sort(recent);
    const cold = rest.filter((l) => now - activity(l) > COLD_AFTER_DAYS * DAY).sort(recent);
    return [
      { key: 'new', title: t(lang, 'crmSecNew'), tone: 'text-blue-700', leads: fresh },
      { key: 'overdue', title: t(lang, 'crmSecOverdue'), tone: 'text-red-700', leads: overdue },
      { key: 'lapsed', title: t(lang, 'crmSecLapsedDue'), tone: 'text-orange-700', leads: lapsed },
      { key: 'cold', title: t(lang, 'crmSecCold'), tone: 'text-zinc-500', leads: cold },
    ].filter((s) => s.leads.length > 0);
  }
  if (tab === 'CONSULT' || tab === 'LAPSED') {
    const todo = leads.filter((l) => lacksNextStep(l, now));
    const inwork = leads.filter((l) => hasNextStep(l, now));
    const sub = (ls: Lead[]): Section[] => {
      if (tab === 'CONSULT') {
        const consultAt = (l: Lead) => (l.consultAt ? new Date(l.consultAt).getTime() : 0);
        return [
          { key: 'stalled', title: t(lang, 'crmSecStalled'), tone: 'text-red-700', leads: ls.filter((l) => l.consultState === 'DONE' || l.consultState === 'NO_SHOW').sort((a, b) => consultAt(b) - consultAt(a)) },
          { key: 'upcoming', title: t(lang, 'crmSecConsultUpcoming'), tone: 'text-blue-700', leads: ls.filter((l) => l.consultState === 'UPCOMING').sort((a, b) => consultAt(a) - consultAt(b)) },
        ];
      }
      const visit = (l: Lead) => new Date(l.lastVisitAt ?? 0).getTime();
      const ago = (l: Lead) => (now - visit(l)) / DAY;
      const by = (lo: number, hi: number) => ls.filter((l) => ago(l) > lo && ago(l) <= hi).sort((a, b) => visit(b) - visit(a));
      return [
        { key: 'l1', title: t(lang, 'crmSecLapsed1'), tone: 'text-orange-700', leads: by(LAPSED_AFTER_DAYS, 60) },
        { key: 'l2', title: t(lang, 'crmSecLapsed2'), tone: 'text-zinc-600', leads: by(60, 180) },
        { key: 'l3', title: t(lang, 'crmSecLapsed3'), tone: 'text-zinc-500', leads: by(180, Infinity) },
      ];
    };
    return [
      ...sub(todo).map((x) => ({ ...x, key: `todo-${x.key}`, group: 'todo' as const })),
      ...sub(inwork).map((x) => ({ ...x, key: `inwork-${x.key}`, group: 'inwork' as const })),
    ].filter((x) => x.leads.length > 0);
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

export default function LeadsManager({ initialLeads, language = null, restricted = false, initialTab = null }: { initialLeads: Lead[]; language?: Language | null; restricted?: boolean; initialTab?: string | null }) {
  const [leads, setLeads] = useState(initialLeads);
  const [tab, setTab] = useState<AnyTab | null>(initialTab === 'needs-reply' && !restricted ? REPLY : null);
  const [queue, setQueue] = useState<NeedsReplyQueue | null>(null);
  const [queueFailed, setQueueFailed] = useState(false);
  const [outdatedTotal, setOutdatedTotal] = useState<number | null>(null);
  const [q, setQ] = useState('');
  const [source, setSource] = useState('');
  const [academyOnly, setAcademyOnly] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [reaching, setReaching] = useState<Lead | null>(null);
  const [now] = useState(() => Date.now());
  const [funnels, setFunnels] = useState<Record<number, LeadFunnel[]>>({});
  const [badges, setBadges] = useState<Record<number, LeadListBadge>>({});
  const [ownerView, setOwnerView] = useState<LeadOwnersView | null>(null);
  const [mineChoice, setMineChoice] = useState<boolean | null>(() => {
    try { const v = sessionStorage.getItem('leadsMine'); return v === null ? null : v === '1'; } catch { return null; }
  });
  const setMineOnly = (on: boolean) => {
    setMineChoice(on);
    try { sessionStorage.setItem('leadsMine', on ? '1' : '0'); } catch { /* ignore */ }
  };

  useEffect(() => {
    fetch('/api/leads/owners', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)).then((d) => { if (d) setOwnerView(d); }).catch(() => undefined);
  }, []);

  async function setAcademyDefault(userId: number) {
    const res = await fetch('/api/leads/owners/default', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId }) });
    if (!res.ok) return;
    const r = await fetch('/api/leads/owners', { cache: 'no-store' });
    if (r.ok) setOwnerView(await r.json());
  }

  useEffect(() => {
    fetch('/api/leads/funnels', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : {})).then(setFunnels).catch(() => undefined);
  }, []);

  useEffect(() => {
    fetch('/api/leads/badges', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : {})).then(setBadges).catch(() => undefined);
  }, []);

  const loadQueue = useMemo(() => () => {
    fetch('/api/metrics/needs-reply', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error('queue'))))
      .then((d: NeedsReplyQueue) => { setQueue(d); setQueueFailed(false); })
      .catch(() => setQueueFailed(true));
  }, []);

  useEffect(() => {
    if (restricted) return undefined;
    loadQueue();
    const id = setInterval(loadQueue, 30_000);
    return () => clearInterval(id);
  }, [restricted, loadQueue]);

  useEffect(() => {
    if (restricted) return;
    fetch('/api/leads/outdated?page=0', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)).then((d) => { if (d) setOutdatedTotal(d.total); }).catch(() => undefined);
  }, [restricted]);

  const sources = useMemo(() => Array.from(new Set(leads.map((l) => l.source))).sort(), [leads]);
  const mineOnly = ownerView != null && (mineChoice ?? !ownerView.canSetDefault);
  const mineSet = useMemo(() => new Set(ownerView?.mine ?? []), [ownerView]);
  const base = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return leads.filter((l) =>
      (!source || l.source === source) && (!academyOnly || l.academy) && (!mineOnly || mineSet.has(l.id)) &&
      (!needle || [l.name, l.phoneNumber, l.email, l.source, l.lastNote].some((v) => v?.toLowerCase().includes(needle))));
  }, [leads, q, source, academyOnly, mineOnly, mineSet]);
  const othersWaiting = useMemo(
    () => (ownerView && mineOnly ? leads.filter((l) => !mineSet.has(l.id) && isActive(l) && needsAction(l, now)).length : 0),
    [leads, ownerView, mineOnly, mineSet, now]);
  const hasAcademy = useMemo(() => leads.some((l) => l.academy), [leads]);
  const hasConsult = useMemo(() => leads.some((l) => l.consultState), [leads]);
  const hasLapsed = useMemo(() => leads.some((l) => isLapsed(l, now)), [leads, now]);

  const inTab = (l: Lead, x: Tab): boolean =>
    x === 'ALL' ? l.status !== 'SPAM' : x === 'CONSULT' ? isActive(l) && l.consultState != null : x === 'LAPSED' ? isLapsed(l, now) : x === 'ACTION' ? (l.status === 'NEW' || isOverdue(l, now)) : l.status === x;
  const counts = useMemo(
    () => Object.fromEntries(TABS.map((x) => [x, base.filter((l) => inTab(l, x) && (x !== 'ACTION' || needsAction(l, now)) && ((x !== 'CONSULT' && x !== 'LAPSED') || lacksNextStep(l, now))).length])) as Record<Tab, number>,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base],
  );
  const tabTotals = useMemo(
    () => ({ CONSULT: base.filter((l) => inTab(l, 'CONSULT')).length, LAPSED: base.filter((l) => inTab(l, 'LAPSED')).length }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base],
  );
  const replyCount = queue?.count ?? 0;
  const activeTab: AnyTab = tab ?? (replyCount > 0 ? REPLY : counts.ACTION > 0 ? 'ACTION' : 'ALL');
  const reply = activeTab === REPLY;
  const archive = activeTab === OUTDATED;
  const sections = archive || reply ? [] : buildSections(base.filter((l) => inTab(l, activeTab as Tab)), activeTab as Tab, now, language);

  async function noAnswer(l: Lead) {
    setBusyId(l.id);
    const res = await fetch(`/api/leads/${l.id}/attempts`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    setBusyId(null);
    if (res.ok) {
      const next = new Date(Date.now() + 2 * DAY).toISOString();
      setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status: 'CONTACTED', callbackAt: next, attempts: x.attempts + 1, closeReady: x.attempts + 1 >= 3 } : x)));
    }
  }

  async function reachedOut(l: Lead, body: ReachedOutBody): Promise<boolean> {
    const res = await fetch(`/api/leads/${l.id}/reached-out`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!res.ok) return false;
    const d = await fetch(`/api/leads/${l.id}`, { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    const next: string | null = d ? d.lead.callbackAt : null;
    setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status: 'CONTACTED', callbackAt: next, attempts: 0, closeReady: false } : x)));
    return true;
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
      : x === 'LAPSED' ? t(language, 'crmTabLapsed')
      : x === 'CONTACTED' ? t(language, 'crmSecProgress')
        : x === 'ALL' ? t(language, 'leadsFilterAll')
          : t(language, `leadStatus${x}` as 'leadStatusNEW');


  return (
    <div className="flex min-w-0 max-w-full flex-col gap-4 overflow-x-clip">
      <div className={`flex flex-col gap-2 sm:flex-row ${archive || reply ? 'hidden' : ''}`}>
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
        {!restricted && (
          <button
            onClick={() => setTab(REPLY)}
            aria-pressed={reply}
            data-testid="tab-needs-reply"
            className={`flex min-h-11 items-center gap-2 rounded-full px-3 py-1 text-sm ring-1 sm:min-h-0 ${reply ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300 hover:bg-zinc-50'}`}
          >
            {t(language, 'nrTab')}
            <span className={`rounded-full px-1.5 text-xs tabular-nums ${reply ? 'bg-white/20' : replyCount > 0 ? 'bg-red-600 text-white' : 'bg-zinc-100 text-zinc-600'}`}>{replyCount}</span>
          </button>
        )}
        {TABS.filter((x) => (x !== 'CONSULT' || hasConsult) && (x !== 'LAPSED' || hasLapsed)).map((x) => (
          <button
            key={x}
            onClick={() => setTab(x)}
            title={x === 'CONSULT' || x === 'LAPSED' ? tf(language, 'crmTabCountHint', { n: counts[x], total: tabTotals[x] }) : undefined}
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
        {ownerView && !archive && (
          <button
            onClick={() => setMineOnly(!mineOnly)}
            aria-pressed={mineOnly}
            className={`rounded-full px-3 py-1 text-sm ring-1 ${mineOnly ? 'bg-emerald-700 text-white ring-emerald-700' : 'text-emerald-800 ring-emerald-300 hover:bg-emerald-50'}`}
          >
            {t(language, 'ownersMine')}
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

      {ownerView?.canSetDefault && hasAcademy && !archive && (
        <label className="flex flex-wrap items-center gap-2 text-xs text-zinc-600">
          <span>{t(language, 'ownerAcademyDefault')}</span>
          <select
            value={ownerView.academyDefault ?? ''} onChange={(e) => setAcademyDefault(Number(e.target.value))}
            className="rounded border border-zinc-300 px-2 py-1 text-sm text-zinc-900"
          >
            {ownerView.academyDefault == null && <option value="" disabled />}
            {ownerView.candidates.map((c) => <option key={c.userId} value={c.userId}>{c.name}</option>)}
          </select>
          <span className="text-zinc-500">{t(language, 'ownerAcademyDefaultHint')}</span>
        </label>
      )}

      {reply && <NeedsReplyPanel queue={queue} failed={queueFailed} language={language} onChanged={loadQueue} />}
      {archive && <OutdatedPanel language={language} onTotal={setOutdatedTotal} />}

      {activeTab === 'ACTION' && (
        <p className="rounded-lg bg-zinc-50 px-3 py-2 text-xs text-zinc-600 ring-1 ring-zinc-200">{t(language, 'crmActionHint')}</p>
      )}
      {activeTab === 'ACTION' && counts.ACTION === 0 && (
        <p className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800 ring-1 ring-green-200">{t(language, 'crmAllDone')}</p>
      )}
      {sections.length === 0 && !archive && !reply && activeTab !== 'ACTION' && <p className="text-sm text-zinc-500">{t(language, 'leadsEmpty')}</p>}

      {(() => {
        const todo = sections.filter((x) => x.group === 'todo');
        const inwork = sections.filter((x) => x.group === 'inwork');
        const plain = sections.filter((x) => !x.group);
        const total = (xs: Section[]) => xs.reduce((n, x) => n + x.leads.length, 0);
        const renderSection = (s: Section) => (
        <section key={s.key} className="flex min-w-0 flex-col gap-2">
          {s.title && (
            <h3 className={`flex items-start gap-2 font-sans text-xs font-semibold leading-snug ${s.tone}`}>
              {s.title}<span className="tabular-nums text-zinc-400">{s.leads.length}</span>
            </h3>
          )}
          <ul className="flex min-w-0 flex-col divide-y divide-zinc-200 rounded-lg ring-1 ring-zinc-200">
            {s.leads.map((l) => {
              const overdue = isOverdue(l, now);
              const waiting = l.status === 'NEW' ? span(now - activity(l), language) : null;
              const late = overdue && l.callbackAt ? span(now - new Date(l.callbackAt).getTime(), language) : null;
              const reason: { text: string; cls: string } | null =
                !isActive(l) ? null
                : l.closeReady ? { text: t(language, 'crmReasonClose'), cls: 'text-red-700' }
                : overdue ? { text: isLapsed(l, now) ? '' : `${l.consultState === 'DONE' || l.consultState === 'NO_SHOW' ? t(language, 'crmReasonConsult') + '. ' : ''}${t(language, 'crmReasonOverdue')} ${late}`, cls: 'text-red-700' }
                : l.status === 'NEW' ? { text: `${t(language, 'crmReasonNew')} ${waiting}`, cls: 'text-blue-700' }
                : null;
              const nextStepSet = hasNextStep(l, now);
              return (
                <li key={l.id} className={`flex min-w-0 flex-col gap-2 p-3 lg:flex-row lg:items-center lg:gap-4 ${overdue ? 'bg-red-50/60' : ''}`}>
                  <Link href={`/admin/leads/${l.id}`} className="block min-w-0 flex-1 hover:underline">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="max-w-full break-words font-medium">{l.name ?? l.phoneNumber ?? l.email}</span>
                      <LeadIcons b={badges[l.id]} language={language} />
                      {!(overdue && l.status === 'CONTACTED') && (
                        <span className={`rounded px-1.5 py-0.5 text-xs ${STATUS_STYLE[l.status]}`}>{t(language, `leadStatus${l.status}` as 'leadStatusNEW')}</span>
                      )}
                      {overdue && l.callbackAt && !isLapsed(l, now) && (
                        <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-800">{t(language, 'crmCallbackAt')} {fmt(l.callbackAt, language)}</span>
                      )}
                      {nextStepSet && l.callbackAt && (
                        <span className="rounded bg-green-100 px-1.5 py-0.5 text-xs text-green-800">{t(language, 'crmNextStepSet')} {fmt(l.callbackAt, language)}</span>
                      )}
                      {isActive(l) && l.callbackAt == null && (
                        <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-800">{t(language, 'crmNextStepNone')}</span>
                      )}
                      {l.attempts > 0 && isActive(l) && (
                        <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-xs text-zinc-700">{l.attempts}/3 {t(language, 'crmAttempts')}</span>
                      )}
                      {l.academy && <span className="rounded bg-violet-100 px-1.5 py-0.5 text-xs text-violet-800">{t(language, 'crmAcademy')}</span>}
                      {ownerView?.owners[l.id] && isActive(l) && (l.academy || ownerView.owners[l.id].kind !== 'TEAM') && (
                        <span className={`rounded-full px-1.5 py-0.5 text-xs ${ownerView.owners[l.id].userId === ownerView.meId ? 'bg-emerald-100 text-emerald-900' : 'bg-indigo-100 text-indigo-900'}`} title={t(language, 'ownerLabel')}>
                          {ownerView.owners[l.id].name ?? t(language, 'ownerTeam')}
                        </span>
                      )}
                      {l.consultState === 'UPCOMING' && l.consultAt && (
                        <span className="rounded bg-sky-100 px-1.5 py-0.5 text-xs text-sky-800">{t(language, 'crmConsultUpcoming')} {fmt(l.consultAt, language)}</span>
                      )}
                      {l.consultState === 'NO_SHOW' && l.consultAt && isActive(l) && (
                        <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-800">{t(language, 'crmConsultNoShow')} {span(now - new Date(l.consultAt).getTime(), language)} {t(language, 'crmAgo')}</span>
                      )}
                      {l.consultState === 'DONE' && l.consultAt && isActive(l) && (
                        <span className="rounded bg-orange-100 px-1.5 py-0.5 text-xs text-orange-800">{t(language, 'crmConsultDone')} {span(now - new Date(l.consultAt).getTime(), language)} {t(language, 'crmAgo')}</span>
                      )}
                      {l.lastVisitAt && isLapsed(l, now) && (
                        <span className="rounded bg-orange-100 px-1.5 py-0.5 text-xs text-orange-800">{t(language, 'crmLastVisit')} {fmt(l.lastVisitAt, language).replace(/,? \d+:\d+.*$/, '')} ({span(now - new Date(l.lastVisitAt).getTime(), language)} {t(language, 'crmAgo')})</span>
                      )}
                      {(funnels[l.id] ?? []).filter((f) => f.state !== 'NOT_APPLIED' || f.canApply).map((f) => (
                        <span key={f.key} className={`rounded-full px-1.5 py-0.5 text-xs ${FUNNEL_TONE[f.state]}`} title={t(language, `funnelName_${f.key}` as 'funnelName_academy')}>
                          {t(language, 'funnelChip')}: {t(language, `funnelState_${f.state}` as 'funnelState_ACTIVE').toLowerCase()}
                        </span>
                      ))}
                    </div>
                    {reason && reason.text && (
                      <div className={`mt-0.5 flex items-start gap-1.5 text-xs font-medium ${reason.cls}`}>
                        <span aria-hidden className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-current" />{reason.text}
                      </div>
                    )}
                    <div className="break-words text-xs text-zinc-500">
                      {l.name && l.phoneNumber ? `${l.phoneNumber} · ` : ''}{l.source}
                      {l.adsAssisted && <span title={l.adFirstCampaign ?? l.adFirstSource ?? ''} className="ml-1 inline-block rounded-full bg-amber-100 px-1.5 py-px text-[11px] font-medium text-amber-800 align-middle">{t(language, 'leadAdsAssisted')}</span>}
                      {' · '}{l.channel}
                      {l.touches > 1 ? ` · ${l.touches} ${t(language, 'leadTouches')}` : ''} · {fmt(new Date(activity(l)).toISOString(), language)}
                    </div>
                    {l.lastNote && (
                      <div className="mt-0.5 line-clamp-2 break-words text-xs text-zinc-700">
                        <span className="text-zinc-400">{l.lastNoteBy}: </span>{l.lastNote}
                      </div>
                    )}
                  </Link>
                  <LeadActions
                    lead={l} now={now} language={language} busy={busyId === l.id} withContact
                    handlers={{ onReachedOut: () => setReaching(l), onNoAnswer: () => noAnswer(l), onCloseLost: () => closeLost(l), onSpam: () => setStatus(l, 'SPAM') }}
                  />
                </li>
              );
            })}
          </ul>
        </section>
        );
        return (
          <>
            {plain.map(renderSection)}
            {todo.length > 0 && (
              <div className="flex min-w-0 flex-col gap-3">
                <h2 className="flex items-center gap-2 font-sans text-sm font-semibold text-red-700">
                  {t(language, 'crmGroupNoStep')}<span className="tabular-nums text-zinc-400">{total(todo)}</span>
                </h2>
                {todo.map(renderSection)}
              </div>
            )}
            {inwork.length > 0 && (
              <details className="group flex min-w-0 flex-col gap-3">
                <summary className="flex cursor-pointer items-center gap-2 font-sans text-sm font-semibold text-green-800">
                  {t(language, 'crmGroupInWork')}<span className="tabular-nums text-zinc-400">{total(inwork)}</span>
                </summary>
                <div className="mt-3 flex min-w-0 flex-col gap-3">{inwork.map(renderSection)}</div>
              </details>
            )}
          </>
        );
      })()}
      <ReminderSheet
        open={reaching != null} title={t(language, 'remSheetTitle')} subtitle={reaching ? (reaching.name ?? reaching.phoneNumber ?? reaching.email ?? '') : ''}
        language={language} onClose={() => setReaching(null)} onSubmit={(body) => (reaching ? reachedOut(reaching, body) : Promise.resolve(false))}
        onLost={async (b) => {
          if (!reaching) return false;
          const res = await fetch(`/api/leads/${reaching.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'LOST', ...b }) });
          if (res.ok) setLeads((prev) => prev.map((x) => (x.id === reaching.id ? { ...x, status: 'LOST', closeReady: false } : x)));
          return res.ok;
        }}
      />
    </div>
  );
}
