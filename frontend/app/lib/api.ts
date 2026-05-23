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

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  listProviders: (opts?: { includeInactive?: boolean }): Promise<Provider[]> =>
    fetch(`${base()}/api/providers${opts?.includeInactive ? '?all=true' : ''}`, { cache: 'no-store' }).then(jsonOrThrow),

  createProvider: (body: ProviderCreateRequest): Promise<Provider> =>
    fetch(`${base()}/api/providers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(jsonOrThrow),

  patchProvider: (id: number, body: ProviderPatchRequest): Promise<Provider> =>
    fetch(`${base()}/api/providers/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(jsonOrThrow),

  deleteProvider: (id: number): Promise<void> =>
    fetch(`${base()}/api/providers/${id}`, { method: 'DELETE' })
      .then((r) => {
        if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
      }),

  listPeriods: (): Promise<PayPeriod[]> =>
    fetch(`${base()}/api/pay-periods`, { cache: 'no-store' }).then(jsonOrThrow),

  getPeriod: (id: number): Promise<PayPeriodDetail> =>
    fetch(`${base()}/api/pay-periods/${id}`, { cache: 'no-store' }).then(jsonOrThrow),

  createPeriod: (body: PayPeriodCreateRequest): Promise<PayPeriod> =>
    fetch(`${base()}/api/pay-periods`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(jsonOrThrow),

  upsertEntry: (periodId: number, providerId: number, body: PeriodEntryUpsertRequest): Promise<PeriodEntry> =>
    fetch(`${base()}/api/pay-periods/${periodId}/entries/${providerId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(jsonOrThrow),

  getSettlements: (periodId: number): Promise<Settlement[]> =>
    fetch(`${base()}/api/pay-periods/${periodId}/settlements`, { cache: 'no-store' }).then(jsonOrThrow),

  deletePeriod: (periodId: number): Promise<void> =>
    fetch(`${base()}/api/pay-periods/${periodId}`, { method: 'DELETE' })
      .then((r) => {
        if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
      }),
};
