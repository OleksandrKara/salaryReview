import 'server-only';

import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import type {
  AbuseBlocksData,
  AppUser,
  KbArticle,
  ManualCredit,
  Me,
  Sop,
  MarketingAnalyticsData,
  MarketingContactsData,
  MarketingDashboardData,
  OwnerCustomer,
  OwnerOverviewData,
  PrepaidPackage,
  Provider,
  ProviderDetail,
  ProviderPayout,
  RetentionReport,
  RetentionSeries,
  Redo,
  RevenuePulse,
  SettlementPreview,
  SquareRosterEntry,
  SuspiciousBooking,
  CancelledAppointment,
  ManagerTimesheet,
  AdminTimesheet,
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
  // a restart). Bounce to the homepage rather than rendering a data page with an error. redirect()
  // throws NEXT_REDIRECT, so it must not be wrapped in a try/catch at the call site.
  if (res.status === 401) redirect('/');
  if (res.status === 204) return null as T;
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`);
  return (await res.json()) as T;
}

export const serverApi = {
  getMe: () => serverFetch<Me>(`/api/me`),

  // KB articles, role-filtered by the backend using the session.
  listKbArticles: () => serverFetch<KbArticle[]>(`/api/kb-articles`),

  // SOPs, audience-filtered by the backend using the session.
  listSops: () => serverFetch<Sop[]>(`/api/sops`),

  // Provider retention analytics (owner + manager, view-only for the latter).
  getRetention: (year: number, month: number) =>
    serverFetch<RetentionReport>(`/api/owner/retention?year=${year}&month=${month}`),

  getRetentionSeries: (fromYear: number, fromMonth: number, toYear: number, toMonth: number, provider?: string) =>
    serverFetch<RetentionSeries>(
      `/api/owner/retention/series?fromYear=${fromYear}&fromMonth=${fromMonth}&toYear=${toYear}&toMonth=${toMonth}` +
        (provider ? `&provider=${encodeURIComponent(provider)}` : ''),
    ),

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

  listOwnerCustomers: () => serverFetch<OwnerCustomer[]>(`/api/owner-customers`),

  listRedos: () => serverFetch<Redo[]>(`/api/redos`),

  listManualCredits: () => serverFetch<ManualCredit[]>(`/api/manual-credits`),

  getRevenuePulse: (year: number, month: number) =>
    serverFetch<RevenuePulse>(`/api/owner/pulse?year=${year}&month=${month}`),

  listSuspicious: (year: number, month: number, half: 'FIRST' | 'SECOND', providerId: number) =>
    serverFetch<SuspiciousBooking[]>(
      `/api/suspicious?year=${year}&month=${month}&half=${half}&providerId=${providerId}`
    ),

  // Manager time tracking: a manager's own month; the owner's payroll view of all managers.
  getMyTimesheet: (year: number, month: number) =>
    serverFetch<ManagerTimesheet>(`/api/time/me?year=${year}&month=${month}`),
  getAdminTimesheet: (year: number, month: number) =>
    serverFetch<AdminTimesheet>(`/api/time/admin?year=${year}&month=${month}`),

  // Owner-only: cancelled appointments (CANCELLED_BY_SELLER) for one provider × half, for review.
  listCancellations: (year: number, month: number, half: 'FIRST' | 'SECOND', providerId: number) =>
    serverFetch<CancelledAppointment[]>(
      `/api/cancellations?year=${year}&month=${month}&half=${half}&providerId=${providerId}`
    ),

  // Provider self-view: their own no-notes suspicious bookings (read-only). Server scopes to the
  // authenticated provider — no providerId param here.
  getMySuspicious: (year: number, month: number, half: 'FIRST' | 'SECOND') =>
    serverFetch<SuspiciousBooking[]>(
      `/api/settlements/me/suspicious?year=${year}&month=${month}&half=${half}`
    ),

  getOwnerOverview: (fromYear: number, fromMonth: number, toYear: number, toMonth: number) =>
    serverFetch<OwnerOverviewData>(
      `/api/owner/overview?fromYear=${fromYear}&fromMonth=${fromMonth}&toYear=${toYear}&toMonth=${toMonth}`
    ),

  getMarketingDashboard: (slug?: string) =>
    serverFetch<MarketingDashboardData>(`/api/owner/marketing${slug ? `?slug=${encodeURIComponent(slug)}` : ''}`),

  getMarketingContacts: () => serverFetch<MarketingContactsData>('/api/owner/marketing/contacts'),

  getAbuseBlocks: () => serverFetch<AbuseBlocksData>('/api/owner/marketing/abuse-blocks'),

  /** from/to are ISO dates (yyyy-MM-dd); omitting both defaults to month-to-date on the backend. */
  getMarketingAnalytics: (from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    const qs = params.toString();
    return serverFetch<MarketingAnalyticsData>(`/api/owner/marketing/analytics${qs ? `?${qs}` : ''}`);
  },
};
