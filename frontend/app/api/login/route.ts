import { cookies } from 'next/headers';

// Validate credentials against the backend and adopt its server session. The backend authenticates
// (Spring Security form login) and returns a JSESSIONID; we hold that session id in our own httpOnly
// `sid` cookie (the browser never talks to the backend directly) and forward it on every proxied
// call. `role` is stored too so the proxy can route by role without a round-trip.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// Pull the JSESSIONID value out of the backend's Set-Cookie header(s).
function sessionIdFrom(res: Response): string | null {
  const setCookies =
    typeof res.headers.getSetCookie === 'function'
      ? res.headers.getSetCookie()
      : [res.headers.get('set-cookie') ?? ''];
  for (const c of setCookies) {
    const m = /JSESSIONID=([^;]+)/.exec(c);
    if (m) return m[1];
  }
  return null;
}

// Cookies must be Secure over HTTPS (or the browser sends them on HTTP downgrades) and must NOT be
// Secure over plain HTTP (or the browser drops them). Auto-detect HTTPS from the reverse proxy's
// X-Forwarded-Proto; COOKIE_SECURE=true forces it on regardless.
function secureCookie(req: Request): boolean {
  if (process.env.COOKIE_SECURE === 'true') return true;
  return req.headers.get('x-forwarded-proto') === 'https';
}

export async function POST(req: Request): Promise<Response> {
  const { username, password } = await req
    .json()
    .catch(() => ({} as Record<string, string>));
  if (!username || !password) return new Response('Missing credentials', { status: 400 });

  // Spring form login consumes application/x-www-form-urlencoded.
  const form = new URLSearchParams({ username, password });
  const res = await fetch(`${BACKEND}/api/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form.toString(),
    redirect: 'manual',
  });
  if (!res.ok) return new Response('Invalid credentials', { status: 401 });

  const sid = sessionIdFrom(res);
  if (!sid) return new Response('No session from backend', { status: 502 });

  const me = (await res.json().catch(() => ({}))) as {
    role?: string;
    providerId?: number | null;
  };

  const jar = await cookies();
  const opts = {
    // Secure over HTTPS (auto-detected behind a reverse proxy), off on plain HTTP/localhost.
    secure: secureCookie(req),
    path: '/',
    sameSite: 'lax' as const,
    maxAge: 60 * 60 * 12, // 12h
  };
  jar.set('sid', sid, { ...opts, httpOnly: true });
  // Readable by the proxy (and harmless to expose) so it can route by role.
  jar.set('role', me.role ?? '', { ...opts, httpOnly: false });

  return Response.json({ role: me.role ?? null, providerId: me.providerId ?? null });
}
