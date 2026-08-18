import { cookies, headers as nextHeaders } from 'next/headers';
import { SESSION_COOKIE_MAX_AGE_SECONDS, secureCookie } from '../../../lib/authCookies';

// Phase 6.1/6.2 (design.md D12): switches the backend session's active business (via
// BusinessSwitchController, which stores it as an HttpSession attribute — see
// CurrentBusinessContextFilter) and mirrors the result into our own `businessId` cookie, same
// convention as app/api/login/route.ts. Not routed through the generic forwardToBackend helper
// because it needs the backend's response body to update that cookie, not just relay bytes.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function POST(req: Request): Promise<Response> {
  const jar = await cookies();
  const sid = jar.get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const body = await req.text();
  const res = await fetch(`${BACKEND}/api/business/switch`, {
    method: 'POST',
    headers: { Cookie: `JSESSIONID=${sid}`, 'Content-Type': 'application/json' },
    body,
  });
  const bytes = await res.arrayBuffer();
  if (!res.ok) {
    return new Response(bytes.byteLength ? bytes : null, {
      status: res.status,
      headers: { 'Content-Type': res.headers.get('Content-Type') ?? 'application/json' },
    });
  }

  const result = JSON.parse(new TextDecoder().decode(bytes)) as { businessId: number };
  const opts = {
    secure: secureCookie((await nextHeaders()).get('x-forwarded-proto')),
    path: '/',
    sameSite: 'lax' as const,
    maxAge: SESSION_COOKIE_MAX_AGE_SECONDS,
  };
  jar.set('businessId', String(result.businessId), { ...opts, httpOnly: false });

  return new Response(bytes, { status: 200, headers: { 'Content-Type': 'application/json' } });
}
