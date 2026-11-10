import type { Language } from '../../lib/types';

export const TZ = 'America/Los_Angeles';
export const DEFAULT_DAYS = 2;
export const DEFAULT_TIME = '09:00';
export const REMINDER_CHIPS = [1, 2, 3, 5, 7];

export interface ReminderChoice { days: number | null; date: string; time: string; timeSet: boolean }

const locale = (lang: Language | null) => (lang === 'RU' ? 'ru-RU' : 'en-US');

export function ptYmd(ms: number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(ms);
}

export function ptHm(ms: number): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(ms);
}

export function addDays(ymd: string, n: number): string {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

export function daysBetween(fromYmd: string, toYmd: string): number {
  const f = (s: string) => { const [y, m, d] = s.split('-').map(Number); return Date.UTC(y, m - 1, d); };
  return Math.round((f(toYmd) - f(fromYmd)) / 86_400_000);
}

export function formatDay(ymd: string, lang: Language | null, withYear = false): string {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(locale(lang), {
    timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric', year: withYear ? 'numeric' : undefined,
  });
}

export function formatTime(hm: string, lang: Language | null): string {
  const [h, m] = hm.split(':').map(Number);
  return new Date(Date.UTC(2000, 0, 1, h, m)).toLocaleTimeString(locale(lang), { timeZone: 'UTC', hour: 'numeric', minute: '2-digit' });
}

/** Date and time of a reminder instant as shown to the team: always Pacific time. */
export function formatDue(iso: string, lang: Language | null, now: number): string {
  const ms = Date.parse(iso);
  const day = ptYmd(ms);
  const sameYear = day.slice(0, 4) === ptYmd(now).slice(0, 4);
  return `${formatDay(day, lang, !sameYear)}, ${formatTime(ptHm(ms), lang)}`;
}

/** "in 2 days", "tomorrow", "today" or "3 days overdue", by Pacific calendar day. */
export function dueRelative(iso: string, now: number): { n: number; overdue: boolean } {
  const ms = Date.parse(iso);
  const n = daysBetween(ptYmd(now), ptYmd(ms));
  return { n, overdue: ms <= now };
}

export function choiceDate(c: ReminderChoice, now: number): string {
  return c.days != null ? addDays(ptYmd(now), c.days) : c.date;
}

export function choiceInPast(c: ReminderChoice, now: number): boolean {
  const date = choiceDate(c, now);
  if (!date) return true;
  const today = ptYmd(now);
  if (date < today) return true;
  return date === today && c.time <= ptHm(now);
}

/** Request body for the API: chip days, or a picked date, with an optional time of day. */
export function choiceBody(c: ReminderChoice, note: string): { days?: number; date?: string; time?: string; note?: string } {
  const body: { days?: number; date?: string; time?: string; note?: string } = {};
  if (c.days != null) body.days = c.days; else body.date = c.date;
  if (c.timeSet) body.time = c.time;
  if (note.trim()) body.note = note.trim();
  return body;
}

export function chipLabel(n: number, lang: Language | null): string {
  if (lang === 'RU') {
    const w = n % 10 === 1 && n % 100 !== 11 ? 'день' : [2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100) ? 'дня' : 'дней';
    return `${n} ${w}`;
  }
  return n === 1 ? '1 day' : `${n} days`;
}

export function dueParts(iso: string): { date: string; time: string } {
  const ms = Date.parse(iso);
  return { date: ptYmd(ms), time: ptHm(ms) };
}
