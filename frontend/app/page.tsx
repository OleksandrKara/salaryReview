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
        if (me.role === 'PROVIDER') {
          target = '/me';
        } else if (me.role === 'ADS_MANAGER') {
          target = '/owner/marketing';
        } else if (me.role === 'OWNER' && !(await ownerBusinessConfigured(sid))) {
          // A freshly created business (Phase 5.1) has no salon_config row yet — every other
          // owner page (/reports, /owner/overview, ...) 500s on it (SettlementService and
          // friends all throw IllegalStateException on a missing config). Route the very first
          // login straight to the form that creates it, instead of a broken landing page.
          target = '/owner/settings/business';
        } else {
          target = '/reports';
        }
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

async function ownerBusinessConfigured(sid: string): Promise<boolean> {
  try {
    const res = await fetch(`${BACKEND}/api/owner/settings/business`, {
      headers: { Cookie: `JSESSIONID=${sid}` },
      cache: 'no-store',
    });
    if (!res.ok) return true; // fail open — never block an existing owner's landing on this check
    const settings = (await res.json()) as { configured?: boolean };
    return settings.configured !== false;
  } catch {
    return true;
  }
}
