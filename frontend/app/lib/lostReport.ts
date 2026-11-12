import type { LostLead } from './types';

export const LOST_PERIODS = [30, 90, 180, 365];

export interface LostFilters { days: number; sources: string[]; reason: string | null }

export interface SourceSummary { source: string; count: number; share: number; topReasons: { reason: string; count: number }[] }

const UNKNOWN = '';

export function parseLostFilters(q: { days?: string; source?: string | string[]; reason?: string }): LostFilters {
  const days = LOST_PERIODS.includes(Number(q.days)) ? Number(q.days) : 90;
  const raw = q.source === undefined ? [] : Array.isArray(q.source) ? q.source : [q.source];
  const sources = [...new Set(raw.filter((s) => s !== ''))];
  const reason = q.reason && /^[A-Z_]{2,40}$/.test(q.reason) ? q.reason : null;
  return { days, sources, reason };
}

export function lostHref(f: LostFilters): string {
  const p = new URLSearchParams();
  if (f.days !== 90) p.set('days', String(f.days));
  f.sources.forEach((s) => p.append('source', s));
  if (f.reason) p.set('reason', f.reason);
  const qs = p.toString();
  return `/admin/leads/lost-report${qs ? `?${qs}` : ''}`;
}

export function toggleSource(f: LostFilters, source: string): LostFilters {
  const sources = f.sources.includes(source) ? f.sources.filter((s) => s !== source) : [...f.sources, source];
  return { ...f, sources };
}

export const sourceKey = (l: LostLead): string => l.source ?? UNKNOWN;

export function filterLeads(leads: LostLead[], f: LostFilters): LostLead[] {
  return leads.filter((l) => (f.sources.length === 0 || f.sources.includes(sourceKey(l))) && (!f.reason || l.reason === f.reason));
}

function tally<T>(items: T[], key: (x: T) => string): [string, number][] {
  const m = new Map<string, number>();
  items.forEach((x) => m.set(key(x), (m.get(key(x)) ?? 0) + 1));
  return [...m.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}

export function reasonCounts(leads: LostLead[]): { reason: string; count: number }[] {
  return tally(leads, (l) => l.reason).map(([reason, count]) => ({ reason, count }));
}

/** Per-source summary over the leads that pass the reason filter (the source filter is what the cards toggle, so it is not applied here). */
export function sourceSummaries(leads: LostLead[], f: LostFilters): SourceSummary[] {
  const base = leads.filter((l) => !f.reason || l.reason === f.reason);
  return tally(base, sourceKey).map(([source, count]) => ({
    source,
    count,
    share: base.length === 0 ? 0 : count / base.length,
    topReasons: reasonCounts(base.filter((l) => sourceKey(l) === source)).slice(0, 2),
  }));
}
