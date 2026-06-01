// Browser-side API calls.
//
// Everything the browser does goes through same-origin proxy route handlers under /api/* (relative
// URLs). Those handlers run on the Next.js server, hold the httpOnly session cookie, and forward it
// to the backend (BACKEND_URL). So the browser never needs to know the backend's address — no
// hardcoded host, works the same locally and on a server.

import type {
  AppUser,
  FeedbackStatus,
  PrepaidCandidate,
  PrepaidCreateRequest,
  PrepaidPackage,
  PrepaidRedemption,
  UserCreateRequest,
  UserUpdateRequest,
} from './types';

export const api = {
  // Tier grant/revoke (owner/manager).
  grantTier: (providerId: number, year: number, month: number) =>
    proxyVoid(`/api/grants?providerId=${providerId}&year=${year}&month=${month}`, 'POST'),

  revokeTier: (providerId: number, year: number, month: number) =>
    proxyVoid(`/api/grants?providerId=${providerId}&year=${year}&month=${month}`, 'DELETE'),

  // User management (owner).
  createUser: (body: UserCreateRequest) => proxyJson<AppUser>(`/api/users`, 'POST', body),

  updateUser: (id: number, body: UserUpdateRequest) =>
    proxyJson<AppUser>(`/api/users/${id}`, 'PATCH', body),

  deleteUser: (id: number) => proxyVoid(`/api/users/${id}`, 'DELETE'),

  // Provider approve / request-correction on their own month.
  submitFeedback: (year: number, month: number, status: FeedbackStatus, comment: string) =>
    proxyVoid(`/api/feedback?year=${year}&month=${month}`, 'POST', { status, comment }),

  // Prepaid packages (owner/manager).
  createPackage: (body: PrepaidCreateRequest) => proxyJson<PrepaidPackage>(`/api/prepaid`, 'POST', body),

  deletePackage: (id: number) => proxyVoid(`/api/prepaid/${id}`, 'DELETE'),

  getCandidates: (id: number) => proxyGet<PrepaidCandidate[]>(`/api/prepaid/${id}/candidates`),

  redeem: (id: number, body: Omit<PrepaidCandidate, 'counts'>) =>
    proxyJson<PrepaidRedemption>(`/api/prepaid/${id}/redemptions`, 'POST', {
      squareBookingId: body.bookingId,
      serviceVariationId: body.serviceVariationId,
      serviceName: body.serviceName,
      serviceDate: body.date,
      menuPrice: body.menuPrice,
      teamMemberId: body.teamMemberId,
      providerName: body.providerName,
    }),

  undoRedemption: (redemptionId: number) =>
    proxyVoid(`/api/prepaid/redemptions/${redemptionId}`, 'DELETE'),
};

async function proxyVoid(path: string, method: string, body?: unknown): Promise<void> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
}

async function proxyGet<T>(path: string): Promise<T> {
  const res = await fetch(path, { cache: 'no-store' });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return (await res.json()) as T;
}

async function proxyJson<T>(path: string, method: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return (await res.json()) as T;
}
