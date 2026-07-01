import 'server-only';

import { cookies } from 'next/headers';
import type { Me, Sop } from './types';

// Server-side onboarding check for the root layout. Deliberately does NOT use serverApi.serverFetch,
// which redirects on 401 — the layout also wraps the public landing page, where a redirect would loop.
// Returns null (→ not gated) whenever the caller isn't a signed-in manager/provider, so owners and
// anonymous visitors are never blocked.

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

async function get<T>(path: string, sid: string): Promise<T | null> {
  try {
    const res = await fetch(`${BACKEND}${path}`, {
      cache: 'no-store',
      headers: { Cookie: `JSESSIONID=${sid}` },
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export interface GateState {
  blocked: boolean;
  me: Me;
  pending: Sop[]; // unaccepted, audience-matched SOPs with a published version
}

export async function loadOnboardingGate(): Promise<GateState | null> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return null;

  const me = await get<Me>('/api/me', sid);
  if (!me || (me.role !== 'MANAGER' && me.role !== 'PROVIDER')) return null;

  const sops = (await get<Sop[]>('/api/sops', sid)) ?? [];
  const pending = sops.filter((s) => !s.acknowledged && s.currentVersion);
  const blocked = me.preferredLanguage === null || pending.length > 0;
  return { blocked, me, pending };
}
