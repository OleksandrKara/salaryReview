import 'server-only';

// Shared by the login route (issuing `sid`/`role`) and the backend proxy (sliding their expiry
// on every authenticated request — see proxyBackend.ts) so the two can't drift apart.
//
// 30 days: long enough that a provider who only opens the app every ~2 weeks never has to
// re-login, while a manager or owner who's actually active never sees it expire at all (sliding,
// not a fixed wall-clock cap). Matches the backend's spring.session.timeout, which is what
// actually enforces the same window server-side — this is just the browser-facing cookie
// mirroring it.
export const SESSION_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

/** Cookies must be Secure over HTTPS (or the browser sends them on HTTP downgrades) and must NOT
 * be Secure over plain HTTP (or the browser drops them). Auto-detect HTTPS from the reverse
 * proxy's X-Forwarded-Proto header; COOKIE_SECURE=true forces it on regardless (e.g. TLS
 * terminated in a way that doesn't set that header). */
export function secureCookie(forwardedProto: string | null): boolean {
  if (process.env.COOKIE_SECURE === 'true') return true;
  return forwardedProto === 'https';
}
