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

// Every "today"/period boundary in this file resolves against the salon's own business
// timezone rather than the ambient one (server process or browser) this code happens to run
// in — this file runs in both, and neither is guaranteed to be Pacific. Matches the backend's
// own resolveZone() convention (see MarketingDashboardService). Phase 6.3: callers now pass the
// business's real configured timezone (BusinessSettingsDto.timezone, threaded down from each
// tab's page.tsx); DEFAULT_TIME_ZONE is only the fallback for a caller that hasn't been updated
// to pass one, same value as the old hardcoded constant so nothing changes for a caller that
// doesn't (both of today's real businesses are Pacific anyway).
const DEFAULT_TIME_ZONE = 'America/Los_Angeles';

interface YMD {
  year: number;
  month: number; // 1-12
  day: number;
}

function todayInSalonZone(timeZone: string): YMD {
  return dateToYmdInSalonZone(new Date(), timeZone);
}

function dateToYmdInSalonZone(d: Date, timeZone: string): YMD {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(d);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return { year: get('year'), month: get('month'), day: get('day') };
}

function isoDate({ year, month, day }: YMD): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

// Pure calendar-date arithmetic via a UTC-anchored scratch Date — a YMD triple reinterpreted as
// UTC midnight is only ever used here to add/subtract whole days or months, never read back as
// a real instant, so the UTC anchoring is just a safe, zone-independent way to do the math
// (using local-zone Date setters instead would silently reintroduce the server/browser's own
// zone instead of the salon's).
function addDays(ymd: YMD, delta: number): YMD {
  const d = new Date(Date.UTC(ymd.year, ymd.month - 1, ymd.day));
  d.setUTCDate(d.getUTCDate() + delta);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

function addMonths(ymd: YMD, delta: number): YMD {
  const d = new Date(Date.UTC(ymd.year, ymd.month - 1 + delta, 1));
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: 1 };
}

function startOfMonth(ymd: YMD): YMD {
  return { year: ymd.year, month: ymd.month, day: 1 };
}

/** Today's date (yyyy-MM-dd) in the salon's business timezone — used e.g. as the custom-range
 * date picker's upper bound, so "today" there agrees with what "Month to date" etc. compute. */
export function todayIso(timeZone: string = DEFAULT_TIME_ZONE): string {
  return isoDate(todayInSalonZone(timeZone));
}

export function lastNWeeksRange(n: number, timeZone: string = DEFAULT_TIME_ZONE): DateRange {
  const to = todayInSalonZone(timeZone);
  const from = addDays(to, -(n * 7 - 1));
  return { from: isoDate(from), to: isoDate(to) };
}

export function lastNMonthsRange(n: number, timeZone: string = DEFAULT_TIME_ZONE): DateRange {
  const to = todayInSalonZone(timeZone);
  const from = addMonths(startOfMonth(to), -(n - 1));
  return { from: isoDate(from), to: isoDate(to) };
}

export function monthToDateSoFarRange(timeZone: string = DEFAULT_TIME_ZONE): DateRange {
  const today = todayInSalonZone(timeZone);
  return { from: isoDate(startOfMonth(today)), to: isoDate(today) };
}

/** Reads period/from/to off a URLSearchParams (or a plain searchParams object, e.g. a server
 * component's own `searchParams` prop) — defaults to `defaultPeriod` ('mtd', Month to date, unless
 * a tab overrides it — Overview and Funnel both default to 'all' instead, since their numbers are
 * normally read over the whole history rather than just the current month) when period is absent
 * or unrecognized. 'custom' without both from and to falls back to the same default too, rather
 * than sending a half-specified custom range to the backend. */
export function parsePeriodParams(
  searchParams: URLSearchParams | Record<string, string | string[] | undefined>,
  defaultPeriod: PeriodType = 'mtd',
): PeriodSelection {
  const get = (key: string): string | undefined => {
    if (searchParams instanceof URLSearchParams) return searchParams.get(key) ?? undefined;
    const v = searchParams[key];
    return Array.isArray(v) ? v[0] : v;
  };
  const rawPeriod = get('period');
  const period: PeriodType = rawPeriod === 'all' || rawPeriod === 'week' || rawPeriod === 'month'
    || rawPeriod === 'mtd' || rawPeriod === 'custom' ? rawPeriod : defaultPeriod;
  const from = get('from');
  const to = get('to');
  if (period === 'custom' && (!from || !to)) return { period: defaultPeriod };
  return period === 'custom' ? { period, from, to } : { period };
}

/** For tabs that only ever need a from/to bound (Overview, Funnel, Contacts) — 'week'/'month'
 * aren't reachable on those tabs (disabled outside Ads Report), so this deliberately doesn't
 * handle them; Ads Report computes its own week/month ranges via its existing rangeCount presets
 * (lastNWeeksRange/lastNMonthsRange above) instead of going through this helper. */
export function periodToBounds(
  selection: PeriodSelection,
  timeZone: string = DEFAULT_TIME_ZONE,
): { from?: string; to?: string } {
  switch (selection.period) {
    case 'all':
      return {};
    case 'mtd':
      return monthToDateSoFarRange(timeZone);
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
