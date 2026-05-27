// Tiny typed fetch wrapper.
//
// Two different bases:
//  - serverBase: used by server components (running in Node). In Docker compose,
//    this needs to resolve to the backend container, so it reads BACKEND_URL.
//  - clientBase: used in the browser. NEXT_PUBLIC_API_BASE is exposed to JS.
// In dev both default to http://localhost:8080.

import type {
  PayPeriod,
  PayPeriodCreateRequest,
  PayPeriodDetail,
  PeriodEntry,
  PeriodEntryUpsertRequest,
  Provider,
  Settlement,
} from './types';

export interface ProviderCreateRequest {
  name: string;
  displayName: string;
  commissionRate?: number | null;
  cardTipFeeRate?: number | null;
}

export interface ProviderPatchRequest {
  name?: string | null;
  displayName?: string | null;
  commissionRate?: number | null;
  cardTipFeeRate?: number | null;
  active?: boolean | null;
}

const serverBase = process.env.BACKEND_URL ?? 'http://localhost:8080';
const clientBase = process.env.NEXT_PUBLIC_API_BASE ?? 'http://localhost:8080';

function base(): string {
  return typeof window === 'undefined' ? serverBase : clientBase;
}

// Generic typed fetch. T is bound at the call site so TS keeps the response type narrow.
async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${base()}${path}`, { cache: 'no-store', ...init });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return (await res.json()) as T;
}

async function apiVoid(path: string, init?: RequestInit): Promise<void> {
  const res = await fetch(`${base()}${path}`, init);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
}

function jsonInit(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

export const api = {
  listProviders: (opts?: { includeInactive?: boolean }) =>
    apiFetch<Provider[]>(`/api/providers${opts?.includeInactive ? '?all=true' : ''}`),

  createProvider: (body: ProviderCreateRequest) =>
    apiFetch<Provider>(`/api/providers`, jsonInit('POST', body)),

  patchProvider: (id: number, body: ProviderPatchRequest) =>
    apiFetch<Provider>(`/api/providers/${id}`, jsonInit('PATCH', body)),

  deleteProvider: (id: number) =>
    apiVoid(`/api/providers/${id}`, { method: 'DELETE' }),

  listPeriods: () =>
    apiFetch<PayPeriod[]>(`/api/pay-periods`),

  getPeriod: (id: number) =>
    apiFetch<PayPeriodDetail>(`/api/pay-periods/${id}`),

  createPeriod: (body: PayPeriodCreateRequest) =>
    apiFetch<PayPeriod>(`/api/pay-periods`, jsonInit('POST', body)),

  upsertEntry: (periodId: number, providerId: number, body: PeriodEntryUpsertRequest) =>
    apiFetch<PeriodEntry>(`/api/pay-periods/${periodId}/entries/${providerId}`, jsonInit('PUT', body)),

  getSettlements: (periodId: number) =>
    apiFetch<Settlement[]>(`/api/pay-periods/${periodId}/settlements`),

  deletePeriod: (periodId: number) =>
    apiVoid(`/api/pay-periods/${periodId}`, { method: 'DELETE' }),

  // --- Square-sourced month settlement (read via lib/serverApi on the server) ---
  // Grant/revoke run in the browser → go through the same-origin proxy (which holds the credential).
  grantTier: (providerId: number, year: number, month: number) =>
    proxyVoid(`/api/grants?providerId=${providerId}&year=${year}&month=${month}`, 'POST'),

  revokeTier: (providerId: number, year: number, month: number) =>
    proxyVoid(`/api/grants?providerId=${providerId}&year=${year}&month=${month}`, 'DELETE'),
};

async function proxyVoid(path: string, method: string): Promise<void> {
  const res = await fetch(path, { method });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
}
