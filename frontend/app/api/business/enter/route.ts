import { cookies, headers as nextHeaders } from 'next/headers';
import { SESSION_COOKIE_MAX_AGE_SECONDS, secureCookie } from '../../../lib/authCookies';
import { parseBusinessParam, safeAdminPath } from '../../../lib/businessLinks';

// GET /api/business/enter?business=<id>&next=/admin/...: switches the session to that business and
// redirects to `next`, so a deep link (Telegram reminder, digest) lands in the right business on
// the very first render instead of showing "not found" and fixing itself afterwards. A business the
// user cannot access is skipped: they land on `next` in their current business.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

function redirectTo(path: string): Response {
  return new Response(null, { status: 303, headers: { Location: path, 'Cache-Control': 'no-store' } });
}

export async function GET(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const next = safeAdminPath(url.searchParams.get('next')) ?? '/admin/leads';
  const business = parseBusinessParam(url.searchParams.get('business') ?? undefined);
  const jar = await cookies();
  const sid = jar.get('sid')?.value;
  if (!sid) return redirectTo('/');
  if (business === null) return redirectTo(next);

  const res = await fetch(`${BACKEND}/api/business/switch`, {
    method: 'POST',
    headers: { Cookie: `JSESSIONID=${sid}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ businessId: business }),
  }).catch(() => null);
  if (res?.ok) {
    const result = (await res.json()) as { businessId: number };
    jar.set('businessId', String(result.businessId), {
      secure: secureCookie((await nextHeaders()).get('x-forwarded-proto')),
      path: '/',
      sameSite: 'lax',
      maxAge: SESSION_COOKIE_MAX_AGE_SECONDS,
      httpOnly: false,
    });
  }
  return redirectTo(next);
}
