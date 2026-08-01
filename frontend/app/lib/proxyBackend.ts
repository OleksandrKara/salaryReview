import 'server-only';

import { cookies, headers as nextHeaders } from 'next/headers';
import { SESSION_COOKIE_MAX_AGE_SECONDS, secureCookie } from './authCookies';

// Shared same-origin proxy for browser → backend calls that need the session. The browser hits our
// route handler (cookies sent automatically); we forward the JSESSIONID from the httpOnly `sid`
// cookie and relay the backend's status/body. Keeps the session out of client-reachable code.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function forwardToBackend(
  backendPath: string,
  method: string,
  body?: string,
): Promise<Response> {
  const jar = await cookies();
  const sid = jar.get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  // Sliding expiry: every authenticated proxied call refreshes both browser-facing cookies'
  // lifetime, mirroring the backend's own already-sliding HttpSession idle timeout (see
  // application.yml's spring.session.timeout doc comment) — an actively-used session (a
  // manager's all-shift-open tab, the owner's constant use) never hits the wall, while a
  // genuinely idle one (a provider between their ~biweekly visits) still expires on schedule.
  const role = jar.get('role')?.value;
  const forwardedProto = (await nextHeaders()).get('x-forwarded-proto');
  const cookieOpts = {
    secure: secureCookie(forwardedProto),
    path: '/',
    sameSite: 'lax' as const,
    maxAge: SESSION_COOKIE_MAX_AGE_SECONDS,
  };
  jar.set('sid', sid, { ...cookieOpts, httpOnly: true });
  if (role !== undefined) jar.set('role', role, { ...cookieOpts, httpOnly: false });

  const headers: Record<string, string> = { Cookie: `JSESSIONID=${sid}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(`${BACKEND}${backendPath}`, { method, headers, body });
  // Binary-safe passthrough — .text() would corrupt a non-UTF-8 body (e.g. a SOP export ZIP).
  // A 204/empty backend response must not carry a body, or the Response constructor throws.
  const bytes = await res.arrayBuffer();
  const outHeaders: Record<string, string> = { 'Content-Type': res.headers.get('Content-Type') ?? 'application/json' };
  const disposition = res.headers.get('Content-Disposition');
  if (disposition) outHeaders['Content-Disposition'] = disposition;
  return new Response(bytes.byteLength ? bytes : null, { status: res.status, headers: outHeaders });
}
