import 'server-only';

import { cookies } from 'next/headers';
import type { SettlementPreview } from './types';

// Server-only backend calls that carry the owner credential from the httpOnly `auth` cookie.
// Kept separate from lib/api.ts (which is bundled into client components) so `next/headers` never
// leaks into the client bundle.

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

async function serverFetch<T>(path: string): Promise<T> {
  const token = (await cookies()).get('auth')?.value;
  const res = await fetch(`${BACKEND}${path}`, {
    cache: 'no-store',
    headers: token ? { Authorization: `Basic ${token}` } : {},
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`);
  return (await res.json()) as T;
}

export const serverApi = {
  getSettlementPreview: (year: number, month: number) =>
    serverFetch<SettlementPreview>(`/api/settlements/preview?year=${year}&month=${month}`),
};
