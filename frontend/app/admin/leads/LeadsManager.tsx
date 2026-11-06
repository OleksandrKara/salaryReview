'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { t } from '../../lib/i18n';
import type { Language, Lead } from '../../lib/types';

type Tab = 'ACTION' | 'CONTACTED' | 'BOOKED' | 'LOST' | 'ALL';
const TABS: Tab[] = ['ACTION', 'CONTACTED', 'BOOKED', 'LOST', 'ALL'];
const DAY = 86_400_000;
const COLD_AFTER_DAYS = 7;
const STATUS_STYLE: Record<string, string> = {
  NEW: 'bg-blue-100 text-blue-800',
  CONTACTED: 'bg-zinc-100 text-zinc-700',
  BOOKED: 'bg-green-100 text-green-800',
  LOST: 'bg-zinc-100 text-zinc-500',
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

type Section = { key: string; title: string; tone: string; leads: Lead[] };

function buildSections(leads: Lead[], tab: Tab, now: number, lang: Language | null): Section[] {
  const cb = (l: Lead) => (l.callbackAt ? new Date(l.callbackAt).getTime() : Infinity);
  const recent = (a: Lead, b: Lead) => activity(b) - activity(a);
  if (tab === 'ACTION') {
    const overdue = leads.filter((l) => isOverdue(l, now)).sort((a, b) => cb(a) - cb(b));
    const rest = leads.filter((l) => !isOverdue(l, now) && l.status === 'NEW');
    const fresh = rest.filter((l) => now - activity(l) <= COLD_AFTER_DAYS * DAY).sort(recent);
    const cold = rest.filter((l) => now - activity(l) > COLD_AFTER_DAYS * DAY).sort(recent);
    return [
      { key: 'overdue', title: t(lang, 'crmSecOverdue'), tone: 'text-red-700', leads: overdue },
      { key: 'new', title: t(lang, 'crmSecNew'), tone: 'text-blue-700', leads: fresh },
      { key: 'cold', title: t(lang, 'crmSecCold'), tone: 'text-zinc-500', leads: cold },
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

export default function LeadsManager({ initialLeads, language = null }: { initialLeads: Lead[]; language?: Language | null }) {
  const [leads, setLeads] = useState(initialLeads);
  const [tab, setTab] = useState<Tab | null>(null);
  const [q, setQ] = useState('');
  const [source, setSource] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);
  const [now] = useState(() => Date.now());

  const sources = useMemo(() => Array.from(new Set(leads.map((l) => l.source))).sort(), [leads]);
  const base = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return leads.filter((l) =>
      (!source || l.source === source) &&
      (!needle || [l.name, l.phoneNumber, l.email, l.source, l.lastNote].some((v) => v?.toLowerCase().includes(needle))));
  }, [leads, q, source]);

  const inTab = (l: Lead, x: Tab): boolean =>
    x === 'ALL' ? true : x === 'ACTION' ? (l.status === 'NEW' || isOverdue(l, now)) : l.status === x;
  const counts = useMemo(
    () => Object.fromEntries(TABS.map((x) => [x, base.filter((l) => inTab(l, x)).length])) as Record<Tab, number>,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [base],
  );
  const activeTab: Tab = tab ?? (counts.ACTION > 0 ? 'ACTION' : 'ALL');
  const sections = buildSections(base.filter((l) => inTab(l, activeTab)), activeTab, now, language);

  async function markContacted(l: Lead) {
    setBusyId(l.id);
    const res = await fetch(`/api/leads/${l.id}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'CONTACTED' }),
    });
    setBusyId(null);
    if (res.ok) setLeads((prev) => prev.map((x) => (x.id === l.id ? { ...x, status: 'CONTACTED' } : x)));
  }

  const tabLabel = (x: Tab) =>
    x === 'ACTION' ? t(language, 'crmTabAction')
      : x === 'CONTACTED' ? t(language, 'crmSecProgress')
        : x === 'ALL' ? t(language, 'leadsFilterAll')
          : t(language, `leadStatus${x}` as 'leadStatusNEW');

  const quick = 'rounded px-2.5 py-1 text-xs ring-1 ring-zinc-300 hover:bg-zinc-100';

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2 sm:flex-row">
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
        {TABS.map((x) => (
          <button
            key={x}
            onClick={() => setTab(x)}
            className={`flex items-center gap-2 rounded-full px-3 py-1 text-sm ring-1 ${activeTab === x ? 'bg-zinc-900 text-white ring-zinc-900' : 'text-zinc-700 ring-zinc-300 hover:bg-zinc-50'}`}
          >
            {tabLabel(x)}
            <span className={`rounded-full px-1.5 text-xs tabular-nums ${activeTab === x ? 'bg-white/20' : x === 'ACTION' && counts[x] > 0 ? 'bg-red-100 text-red-700' : 'bg-zinc-100 text-zinc-600'}`}>{counts[x]}</span>
          </button>
        ))}
      </div>

      {sections.length === 0 && <p className="text-sm text-zinc-500">{t(language, 'leadsEmpty')}</p>}

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
                      {waiting && <span className="text-xs text-zinc-500">{t(language, 'crmWaiting')} {waiting}</span>}
                    </div>
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
                      {l.status === 'NEW' && (
                        <button className={quick} disabled={busyId === l.id} onClick={() => markContacted(l)}>{t(language, 'crmMarkContacted')}</button>
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
