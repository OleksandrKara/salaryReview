import 'server-only';

import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import type {
  AppUser,
  Me,
  PrepaidPackage,
  Provider,
  ProviderDetail,
  ProviderPayout,
  SettlementPreview,
  SquareRosterEntry,
} from './types';

// Server-only backend calls. Auth is the backend session: we hold its JSESSIONID in our httpOnly
// `sid` cookie and forward it as the Cookie header. Kept separate from lib/api.ts (bundled into
// client components) so `next/headers` never leaks into the client bundle.

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

async function serverFetch<T>(path: string): Promise<T> {
  const sid = (await cookies()).get('sid')?.value;
  const res = await fetch(`${BACKEND}${path}`, {
    cache: 'no-store',
    headers: sid ? { Cookie: `JSESSIONID=${sid}` } : {},
  });
  // No/expired session: the cookie may still be present but the backend session is gone (e.g. after
  // a restart). Bounce to /login rather than rendering a data page with an error. redirect() throws
  // NEXT_REDIRECT, so it must not be wrapped in a try/catch at the call site.
  if (res.status === 401) redirect('/login');
  if (res.status === 204) return null as T;
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`);
  return (await res.json()) as T;
}

export const serverApi = {
  getMe: () => serverFetch<Me>(`/api/me`),

  getSettlementPreview: (year: number, month: number) =>
    serverFetch<SettlementPreview>(`/api/settlements/preview?year=${year}&month=${month}`),

  getMySettlement: (year: number, month: number) =>
    serverFetch<ProviderPayout | null>(`/api/settlements/me?year=${year}&month=${month}`),

  getMyDetail: (year: number, month: number) =>
    serverFetch<ProviderDetail>(`/api/settlements/me/detail?year=${year}&month=${month}`),

  listUsers: () => serverFetch<AppUser[]>(`/api/users`),

  listProviders: () => serverFetch<Provider[]>(`/api/providers?all=true`),

  getSquareRoster: () => serverFetch<SquareRosterEntry[]>(`/api/users/square-roster`),

  getProviderDetail: (year: number, month: number, providerId: number) =>
    serverFetch<ProviderDetail>(`/api/settlements/detail?year=${year}&month=${month}&providerId=${providerId}`),

  listPrepaid: () => serverFetch<PrepaidPackage[]>(`/api/prepaid`),
};
