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
  TelegramSettingsDto,
  TwilioSmsSettingsDto,
  FunnelDashboardData,
  MarketingAdsReportData,
  MarketingAnalyticsData,
  MarketingContactsData,
  MarketingDashboardData,
  MarketingLandingPage,
  OwnerCustomer,
  OwnerOverviewData,
  PrepaidPackage,
  Provider,
  ProviderDetail,
  ProviderPayout,
  RetentionReport,
  RetentionSeries,
  Redo,
  RevenueDayDetail,
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

// Same as serverFetch, but a 404 resolves to null instead of throwing — for single-item lookups
// (a shareable-link detail page) where "doesn't exist" or "not visible to you" is an expected,
// renderable state rather than an error.
async function serverFetchOrNull<T>(path: string): Promise<T | null> {
  const sid = (await cookies()).get('sid')?.value;
  const res = await fetch(`${BACKEND}${path}`, {
    cache: 'no-store',
    headers: sid ? { Cookie: `JSESSIONID=${sid}` } : {},
  });
  if (res.status === 401) redirect('/');
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text()}`);
  return (await res.json()) as T;
}

export const serverApi = {
  getMe: () => serverFetch<Me>(`/api/me`),

  // Open (untriaged) knowledge-gap request count, for the owner's nav badge. Never throws — a
  // missing/disabled RAG feature or a hiccup here must not take down every page's header. A
  // session-expired redirect must still propagate, not be swallowed as "no count".
  getKbRequestOpenCount: async (): Promise<number> => {
    try {
      const { count } = await serverFetch<{ count: number }>(`/api/rag/admin/requests/open-count`);
      return count;
    } catch (e) {
      if (e && typeof e === 'object' && 'digest' in e && String(e.digest).startsWith('NEXT_REDIRECT')) throw e;
      return 0;
    }
  },

  // KB articles, role-filtered by the backend using the session.
  listKbArticles: () => serverFetch<KbArticle[]>(`/api/kb-articles`),

  // One KB article, for the shareable-link detail page — null when it doesn't exist or isn't
  // visible to the caller's role, so the page can render a friendly message instead of a 500.
  getKbArticle: (id: number) => serverFetchOrNull<KbArticle>(`/api/kb-articles/${id}`),

  // SOPs, audience-filtered by the backend using the session.
  listSops: () => serverFetch<Sop[]>(`/api/sops`),

  // One SOP, for the shareable-link detail page — same null-on-404 convention as getKbArticle.
  getSop: (id: number) => serverFetchOrNull<Sop>(`/api/sops/${id}`),

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

  getTelegramSettings: () => serverFetch<TelegramSettingsDto>(`/api/owner/settings/telegram`),

  getTwilioSmsSettings: () => serverFetch<TwilioSmsSettingsDto>(`/api/owner/settings/sms`),

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

  // date is an ISO yyyy-MM-dd string.
  getRevenueDay: (date: string) =>
    serverFetch<RevenueDayDetail>(`/api/owner/pulse/day?date=${encodeURIComponent(date)}`),

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

  // sources is the same comma-separated traffic-source list the client-side api.ts sends (see
  // TrafficSourceFilter) — omitted here on every current call site, so the backend's own "Ads
  // only" default applies to the initial server-rendered load; kept as a plain string (rather
  // than importing the client component's types) since this module is server-only.
  getMarketingDashboard: (slug?: string, sources?: string) => {
    const query = new URLSearchParams();
    if (slug) query.set('slug', slug);
    if (sources) query.set('sources', sources);
    return serverFetch<MarketingDashboardData>(`/api/owner/marketing?${query.toString()}`);
  },

  getMarketingPages: () => serverFetch<MarketingLandingPage[]>('/api/owner/marketing/pages'),

  getMarketingFunnel: (slug?: string, sources?: string) => {
    const query = new URLSearchParams();
    if (slug) query.set('slug', slug);
    if (sources) query.set('sources', sources);
    return serverFetch<FunnelDashboardData[]>(`/api/owner/marketing/funnel?${query.toString()}`);
  },

  getMarketingContacts: () => serverFetch<MarketingContactsData>('/api/owner/marketing/contacts'),

  getAbuseBlocks: () => serverFetch<AbuseBlocksData>('/api/owner/marketing/abuse-blocks'),

  /** from/to are ISO dates (yyyy-MM-dd); omitting both defaults to month-to-date on the backend.
   * sources omitted defaults to "Ads only" on the backend; pass every TrafficSourceKey token for
   * every contact regardless of channel. slug omitted pools every landing page together. */
  getMarketingAnalytics: (from?: string, to?: string, sources?: string[], slug?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (sources) params.set('sources', sources.join(','));
    if (slug) params.set('slug', slug);
    const qs = params.toString();
    return serverFetch<MarketingAnalyticsData>(`/api/owner/marketing/analytics${qs ? `?${qs}` : ''}`);
  },

  /** period is 'week' or 'month'; from/to/sources/slug follow the same conventions as
   * getMarketingAnalytics above. Omitting from/to defaults to the last 8 weeks or last 6 months. */
  getMarketingAdsReport: (period: 'week' | 'month', from?: string, to?: string, sources?: string[], slug?: string) => {
    const params = new URLSearchParams();
    params.set('period', period);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (sources) params.set('sources', sources.join(','));
    if (slug) params.set('slug', slug);
    return serverFetch<MarketingAdsReportData>(`/api/owner/marketing/ads-report?${params.toString()}`);
  },
};
