// Pure helpers for deep links that carry ?business=<id> (Telegram reminders, digests). Kept free of
// Next imports so the node:test suite can load them.

/** A positive integer business id from a query value, else null. */
export function parseBusinessParam(v: string | string[] | undefined): number | null {
  const raw = Array.isArray(v) ? v[0] : v;
  const n = Number(raw);
  return raw !== undefined && Number.isInteger(n) && n > 0 ? n : null;
}

/** Only same-site admin paths may be used as a post-switch destination (no open redirect). */
export function safeAdminPath(next: string | null | undefined): string | null {
  if (!next || !next.startsWith('/admin/') || next.startsWith('//') || next.includes('\\') || /[\r\n]/.test(next)) return null;
  return next;
}

/** URL of the route that switches the session to a business and then lands on `next`. */
export function enterBusinessUrl(businessId: number, next: string): string {
  return `/api/business/enter?business=${businessId}&next=${encodeURIComponent(next)}`;
}

/** Whether a link's business differs from the one the session is acting on. */
export function needsSwitch(wanted: number | null, active: number | undefined): wanted is number {
  return wanted !== null && wanted !== active;
}

/** Next business to try for a lead link that has no (or a wrong) business: any the user can
 * switch to that was not tried yet; null when none are left. */
export function nextBusinessToTry(businessIds: number[], tried: number[]): number | null {
  return businessIds.find((id) => !tried.includes(id)) ?? null;
}

/** Parses "1,2" into [1, 2], dropping junk. */
export function parseTried(v: string | undefined): number[] {
  return (v ?? '').split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0).slice(0, 10);
}
