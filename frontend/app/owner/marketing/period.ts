// Shared period-filter model for every marketing tab (Overview, Contacts, Funnel, Ads Report) —
// see PeriodFilter.tsx for the UI half. Kept as plain, framework-free functions so both the
// client component (PeriodFilter) and each tab's own page.tsx (server component, no hooks
// available) can parse/build the same shape without duplicating the date math.

export type PeriodType = 'all' | 'week' | 'month' | 'mtd' | 'custom';

export interface DateRange {
  from: string;
  to: string;
}

/** The currently-selected period, as it round-trips through the URL (?period=&from=&to=).
 * from/to are only ever meaningful (and only ever set) when period === 'custom' — every other
 * period type computes its own bounds (see periodToBounds). */
export interface PeriodSelection {
  period: PeriodType;
  from?: string;
  to?: string;
}

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function lastNWeeksRange(n: number): DateRange {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - (n * 7 - 1));
  return { from: isoDate(from), to: isoDate(to) };
}

export function lastNMonthsRange(n: number): DateRange {
  const to = new Date();
  const from = new Date(to.getFullYear(), to.getMonth() - (n - 1), 1);
  return { from: isoDate(from), to: isoDate(to) };
}

export function monthToDateSoFarRange(): DateRange {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth(), 1);
  return { from: isoDate(from), to: isoDate(today) };
}

/** Reads period/from/to off a URLSearchParams (or a plain searchParams object, e.g. a server
 * component's own `searchParams` prop) — defaults to 'mtd' (Month to date) when period is absent
 * or unrecognized, per the shared filter's own default. 'custom' without both from and to falls
 * back to 'mtd' too, rather than sending a half-specified custom range to the backend. */
export function parsePeriodParams(searchParams: URLSearchParams | Record<string, string | string[] | undefined>): PeriodSelection {
  const get = (key: string): string | undefined => {
    if (searchParams instanceof URLSearchParams) return searchParams.get(key) ?? undefined;
    const v = searchParams[key];
    return Array.isArray(v) ? v[0] : v;
  };
  const rawPeriod = get('period');
  const period: PeriodType = rawPeriod === 'all' || rawPeriod === 'week' || rawPeriod === 'month'
    || rawPeriod === 'mtd' || rawPeriod === 'custom' ? rawPeriod : 'mtd';
  const from = get('from');
  const to = get('to');
  if (period === 'custom' && (!from || !to)) return { period: 'mtd' };
  return period === 'custom' ? { period, from, to } : { period };
}

/** For tabs that only ever need a from/to bound (Overview, Funnel, Contacts) — 'week'/'month'
 * aren't reachable on those tabs (disabled outside Ads Report), so this deliberately doesn't
 * handle them; Ads Report computes its own week/month ranges via its existing rangeCount presets
 * (lastNWeeksRange/lastNMonthsRange above) instead of going through this helper. */
export function periodToBounds(selection: PeriodSelection): { from?: string; to?: string } {
  switch (selection.period) {
    case 'all':
      return {};
    case 'mtd':
      return monthToDateSoFarRange();
    case 'custom':
      return { from: selection.from, to: selection.to };
    default:
      return {};
  }
}

/** Merges a PeriodSelection into an existing URLSearchParams (or a plain query-string), leaving
 * every other param (slug, etc.) untouched — the same "merge, don't rebuild" requirement
 * MarketingTabs' own tab links and page-selector need to honor so switching tabs/pages doesn't
 * silently reset whatever period is currently selected. */
export function withPeriodParams(current: URLSearchParams, selection: PeriodSelection): URLSearchParams {
  const next = new URLSearchParams(current.toString());
  next.set('period', selection.period);
  if (selection.period === 'custom' && selection.from && selection.to) {
    next.set('from', selection.from);
    next.set('to', selection.to);
  } else {
    next.delete('from');
    next.delete('to');
  }
  return next;
}
