export type Lang = 'EN' | 'RU';

/** "45 s", "3 min 20 s", "2 h 05 min". Null means no data. */
export function formatWait(seconds: number | null | undefined, lang: Lang | null): string {
  if (seconds == null) return '-';
  const ru = lang === 'RU';
  const s = Math.max(0, Math.round(seconds));
  const sec = ru ? 'с' : 's';
  const min = ru ? 'мин' : 'min';
  const hr = ru ? 'ч' : 'h';
  if (s < 60) return `${s} ${sec}`;
  if (s < 3600) {
    const m = Math.floor(s / 60);
    const r = s % 60;
    return r === 0 || m >= 10 ? `${m} ${min}` : `${m} ${min} ${r} ${sec}`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return m === 0 ? `${h} ${hr}` : `${h} ${hr} ${String(m).padStart(2, '0')} ${min}`;
}

export type SpeedTone = 'good' | 'ok' | 'slow' | 'none';

/** Colour band for a median wait: under 5 min good, under 15 ok, above that slow. */
export function speedTone(medianSeconds: number | null | undefined): SpeedTone {
  if (medianSeconds == null) return 'none';
  if (medianSeconds <= 300) return 'good';
  if (medianSeconds <= 900) return 'ok';
  return 'slow';
}

export type RankableManager = { replies: number; medianSeconds: number | null };

/** Fastest median first; managers with no replies in the period go last. Stable for ties. */
export function rankManagers<T extends RankableManager>(rows: T[]): T[] {
  return [...rows].sort((a, b) => {
    if ((a.replies === 0) !== (b.replies === 0)) return a.replies === 0 ? 1 : -1;
    if (a.replies === 0) return 0;
    return (a.medianSeconds ?? Infinity) - (b.medianSeconds ?? Infinity);
  });
}

export type ShiftState = 'none' | 'quiet' | 'active';

/** none: never clocked in; quiet: a shift exists but nothing was answered or processed yet; active: there is something to show. */
export function shiftState(d: { shiftStart: string | null; replies: number; leadsProcessed: number }): ShiftState {
  if (!d.shiftStart) return 'none';
  return d.replies > 0 || d.leadsProcessed > 0 ? 'active' : 'quiet';
}

/** Waiting longer than this on shift is shown red: it matches the "slow" band of speedTone. */
export const SLOW_WAIT_SECONDS = 900;

export function chatHref(phone: string): string {
  return `/admin/messages?phone=${encodeURIComponent(phone)}`;
}

/** Route that opens the lead card. */
export function leadHref(leadId: number): string {
  return `/admin/leads/${leadId}`;
}

/** "Nov 7, 2:45 AM" in salon time: when the unanswered text arrived (wall clock, not shift time). */
export function sinceLabel(iso: string, lang: Lang | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    timeZone: 'America/Los_Angeles', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}
