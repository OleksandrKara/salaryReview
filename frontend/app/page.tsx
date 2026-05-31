import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import Landing from './Landing';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// Homepage. A signed-in user is sent to their app (provider → /me, staff → /reports); everyone else
// sees the landing with its sign-in modal. The session is *validated* (not just "is the cookie
// present?") so a stale `sid` — e.g. after a backend restart drops the in-memory session — falls
// through to the landing instead of bouncing in a redirect loop with the protected pages.
export default async function Home() {
  const sid = (await cookies()).get('sid')?.value;
  let target: string | null = null;
  if (sid) {
    try {
      const res = await fetch(`${BACKEND}/api/me`, {
        headers: { Cookie: `JSESSIONID=${sid}` },
        cache: 'no-store',
      });
      if (res.ok) {
        const me = (await res.json()) as { role?: string };
        target = me.role === 'PROVIDER' ? '/me' : '/reports';
      }
      // 401/other → session is dead; fall through to the landing (no redirect).
    } catch {
      // backend unreachable → show the landing rather than erroring.
    }
  }
  // redirect() throws NEXT_REDIRECT, so it must be called outside the try/catch above.
  if (target) redirect(target);
  return <Landing />;
}
