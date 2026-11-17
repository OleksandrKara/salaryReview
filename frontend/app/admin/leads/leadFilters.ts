import { addDays, ptYmd } from './reminders';

export const DATE_PRESETS = ['today', 'yesterday', 'last7', 'last30', 'thisMonth', 'lastMonth', 'custom'] as const;
export type DatePreset = (typeof DATE_PRESETS)[number];
export type DateFilter = { preset: DatePreset; from: string; to: string } | null;
export type SortField = 'created' | 'activity';
export type LeadFilters = {
  sources: string[];
  statuses: string[];
  created: DateFilter;
  activity: DateFilter;
  sort: SortField | null;
  asc: boolean;
};

export const STATUS_FILTERS = ['NEW', 'CONTACTED', 'BOOKED', 'LOST', 'SPAM'] as const;
export const EMPTY_FILTERS: LeadFilters = { sources: [], statuses: [], created: null, activity: null, sort: null, asc: false };

const YMD = /^\d{4}-\d{2}-\d{2}$/;
type Params = { get(k: string): string | null; getAll(k: string): string[] };

export function activeCount(f: LeadFilters): number {
  return (f.sources.length ? 1 : 0) + (f.statuses.length ? 1 : 0) + (f.created ? 1 : 0) + (f.activity ? 1 : 0);
}

export const isDefault = (f: LeadFilters): boolean => activeCount(f) === 0 && f.sort == null;

/** Resolves a preset to inclusive Los Angeles calendar days. "Custom" keeps the stored dates. */
export function presetRange(preset: DatePreset, now: number, from = '', to = ''): { from: string; to: string } {
  const today = ptYmd(now);
  switch (preset) {
    case 'today': return { from: today, to: today };
    case 'yesterday': { const y = addDays(today, -1); return { from: y, to: y }; }
    case 'last7': return { from: addDays(today, -6), to: today };
    case 'last30': return { from: addDays(today, -29), to: today };
    case 'thisMonth': return { from: `${today.slice(0, 8)}01`, to: today };
    case 'lastMonth': {
      const first = `${today.slice(0, 8)}01`;
      const last = addDays(first, -1);
      return { from: `${last.slice(0, 8)}01`, to: last };
    }
    default: return { from, to };
  }
}

function readDate(p: Params, key: string, now: number): DateFilter {
  const raw = p.get(key);
  if (!raw) return null;
  if ((DATE_PRESETS as readonly string[]).includes(raw) && raw !== 'custom') return { preset: raw as DatePreset, ...presetRange(raw as DatePreset, now) };
  const [from = '', to = ''] = raw.split('_');
  const a = YMD.test(from) ? from : '';
  const b = YMD.test(to) ? to : '';
  return a || b ? { preset: 'custom', from: a, to: b } : null;
}

export function parseFilters(p: Params, now: number = Date.now()): LeadFilters {
  const sort = p.get('sort');
  return {
    sources: p.getAll('src').filter(Boolean),
    statuses: p.getAll('st').map((x) => x.toUpperCase()).filter((x) => (STATUS_FILTERS as readonly string[]).includes(x)),
    created: readDate(p, 'created', now),
    activity: readDate(p, 'activity', now),
    sort: sort === 'created' || sort === 'activity' ? sort : null,
    asc: p.get('dir') === 'asc',
  };
}

const dateParam = (d: DateFilter): string | null => (d == null ? null : d.preset === 'custom' ? `${d.from}_${d.to}` : d.preset);

/** URL form: presets stay relative so a shared link keeps meaning "last 7 days". */
export function filtersToUrlParams(f: LeadFilters): URLSearchParams {
  const q = new URLSearchParams();
  f.sources.forEach((s) => q.append('src', s));
  f.statuses.forEach((s) => q.append('st', s));
  const c = dateParam(f.created);
  const a = dateParam(f.activity);
  if (c) q.set('created', c);
  if (a) q.set('activity', a);
  if (f.sort) { q.set('sort', f.sort); q.set('dir', f.asc ? 'asc' : 'desc'); }
  return q;
}

/** Backend form: explicit Los Angeles days. */
export function filtersToApiParams(f: LeadFilters, offset = 0): URLSearchParams {
  const q = new URLSearchParams();
  f.sources.forEach((s) => q.append('source', s));
  f.statuses.forEach((s) => q.append('status', s));
  if (f.created?.from) q.set('createdFrom', f.created.from);
  if (f.created?.to) q.set('createdTo', f.created.to);
  if (f.activity?.from) q.set('activityFrom', f.activity.from);
  if (f.activity?.to) q.set('activityTo', f.activity.to);
  if (f.sort) { q.set('sort', f.sort); q.set('dir', f.asc ? 'asc' : 'desc'); }
  if (offset) q.set('offset', String(offset));
  return q;
}
