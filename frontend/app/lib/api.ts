// Browser-side API calls.
//
// Everything the browser does goes through same-origin proxy route handlers under /api/* (relative
// URLs). Those handlers run on the Next.js server, hold the httpOnly session cookie, and forward it
// to the backend (BACKEND_URL). So the browser never needs to know the backend's address — no
// hardcoded host, works the same locally and on a server.

import type {
  AppUser,
  CustomerMatch,
  FeedbackStatus,
  NoShowRow,
  PrepaidCandidate,
  PrepaidCreateRequest,
  PrepaidInvoice,
  PrepaidPackage,
  PrepaidRedemption,
  KbArticle,
  KbWriteRequest,
  Language,
  Sop,
  SopCreateRequest,
  SopRosterEntry,
  SopUpdateRequest,
  SopSyncItem,
  SopVersion,
  RagAgentConfigDto,
  TelegramSettingsDto,
  TelegramSettingsUpdateRequest,
  TwilioSmsSettingsDto,
  TwilioSmsSettingsUpdateRequest,
  SmsAutomationSummary,
  SmsMessageDto,
  SmsMessageDirection,
  SmsConversationDto,
  SmsReplyResult,
  Me,
  RagAnswer,
  KbRequest,
  KbRequestStatus,
  KbRequestTarget,
  FunnelAnalysisResult,
  FunnelDashboardData,
  AdSpendEntry,
  MarketingAdsReportData,
  MarketingLtvData,
  MarketingAnalyticsData,
  MarketingCustomerHistory,
  StaffDocument,
  MarketingContactsData,
  MarketingSyncStatusData,
  MarketingDashboardData,
  MarketingLandingPage,
  RagCitation,
  RagDocumentSummary,
  StarterSuggestions,
  TriageClassification,
  TriageResult,
  TimeEntry,
  TimeEntryInput,
  TrafficSourceKey,
  UserCreateRequest,
  UserUpdateRequest,
} from './types';
import { sourcesParam } from '../owner/marketing/TrafficSourceFilter';

export const api = {
  // Manager time tracking. Self actions (clock in/out, own entries) act on the authenticated caller;
  // setManagerRate is owner-only (enforced server-side).
  clockIn: () => proxyJson<TimeEntry>(`/api/time/clock-in`, 'POST', {}),
  clockOut: () => proxyJson<TimeEntry>(`/api/time/clock-out`, 'POST', {}),
  addTimeEntry: (body: TimeEntryInput) => proxyJson<TimeEntry>(`/api/time/entries`, 'POST', body),
  updateTimeEntry: (id: number, body: TimeEntryInput) =>
    proxyJson<TimeEntry>(`/api/time/entries/${id}`, 'PATCH', body),
  deleteTimeEntry: (id: number) => proxyVoid(`/api/time/entries/${id}`, 'DELETE'),
  setManagerRate: (userId: number, usdPerHour: number) =>
    proxyVoid(`/api/time/admin/rate/${userId}`, 'PUT', { usdPerHour }),

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
  submitFeedback: (year: number, month: number, half: 'FIRST' | 'SECOND', status: FeedbackStatus, comment: string) =>
    proxyVoid(`/api/feedback?year=${year}&month=${month}&half=${half}`, 'POST', { status, comment }),

  // Owner/manager clears a provider's response for a period.
  clearFeedback: (providerId: number, year: number, month: number, half: 'FIRST' | 'SECOND') =>
    proxyVoid(`/api/feedback?providerId=${providerId}&year=${year}&month=${month}&half=${half}`, 'DELETE'),

  // Prepaid packages (owner/manager).
  createPackage: (body: PrepaidCreateRequest) => proxyJson<PrepaidPackage>(`/api/prepaid`, 'POST', body),

  deletePackage: (id: number) => proxyVoid(`/api/prepaid/${id}`, 'DELETE'),

  getCandidates: (id: number) => proxyGet<PrepaidCandidate[]>(`/api/prepaid/${id}/candidates`),

  searchPrepaidCustomers: (q: string) =>
    proxyGet<CustomerMatch[]>(`/api/prepaid/customers/search?q=${encodeURIComponent(q)}`),

  getCustomerInvoices: (customerId: string) =>
    proxyGet<PrepaidInvoice[]>(`/api/prepaid/invoices?customerId=${encodeURIComponent(customerId)}`),

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

  // No-show fees (owner/manager). Detected fees auto-credit; these are the override actions.
  listNoShowFees: (year: number, month: number) =>
    proxyGet<NoShowRow[]>(`/api/no-show-fees?year=${year}&month=${month}`),

  confirmNoShowFee: (body: {
    bookingId: string;
    providerId: number;
    amount?: number;
    feePaidDate?: string;
    customerName?: string | null;
    noShowDate?: string;
    note?: string;
  }) => proxyVoid(`/api/no-show-fees/confirm`, 'POST', body),

  suppressNoShowFee: (bookingId: string) =>
    proxyVoid(`/api/no-show-fees/suppress?bookingId=${encodeURIComponent(bookingId)}`, 'POST'),

  clearNoShowFee: (bookingId: string) =>
    proxyVoid(`/api/no-show-fees/${encodeURIComponent(bookingId)}`, 'DELETE'),

  // Force a fresh pull from Square (busts the read cache) — the "Sync now" button.
  syncSquare: () => proxyVoid(`/api/sync`, 'POST'),

  // AI triage (suspicious-booking explainer) — owner clicks Explain on a flagged booking.
  requestTriage: (bookingId: string, year: number, month: number) =>
    proxyJson<TriageResult>(
      `/api/suspicious/${encodeURIComponent(bookingId)}/triage?year=${year}&month=${month}`,
      'POST',
      {},
    ),

  // Record owner's thumbs-up/down on a triage card. correctedClassification is sent only on a
  // thumbs-down when the owner picks the actually-right classification.
  submitTriageFeedback: (
    bookingId: string,
    helpful: boolean,
    correctedClassification: TriageClassification | null,
  ) =>
    proxyVoid(`/api/suspicious/${encodeURIComponent(bookingId)}/triage/feedback`, 'POST', {
      helpful,
      correctedClassification,
    }),

  // RAG knowledge assistant — manager/owner asks a question; backend returns a cited answer.
  askRag: (question: string) => proxyJson<RagAnswer>(`/api/rag/ask`, 'POST', { question }),

  submitRagFeedback: (runId: string, helpful: boolean) =>
    proxyVoid(`/api/rag/ask/feedback`, 'POST', { runId, helpful }),

  // RAG admin (owner). Upload is multipart, so it bypasses proxyJson (which forces JSON).
  listRagDocuments: () => proxyGet<RagDocumentSummary[]>(`/api/rag/admin/documents`),

  uploadRagDocument: async (file: File): Promise<RagDocumentSummary> => {
    const form = new FormData();
    form.append('file', file);
    // No Content-Type header — the browser sets multipart/form-data with the boundary.
    const res = await fetch(`/api/rag/admin/documents`, { method: 'POST', body: form });
    if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
    return (await res.json()) as RagDocumentSummary;
  },

  approveRagDocument: (id: number) =>
    proxyJson<RagDocumentSummary>(`/api/rag/admin/documents/${id}/approve`, 'POST', {}),

  deleteRagDocument: (id: number) => proxyVoid(`/api/rag/admin/documents/${id}`, 'DELETE'),

  getRagConfig: () => proxyGet<RagAgentConfigDto>(`/api/rag/admin/config`),

  updateRagConfig: (body: Omit<RagAgentConfigDto, 'version'>) =>
    proxyJson<RagAgentConfigDto>(`/api/rag/admin/config`, 'POST', body),

  // Telegram 4-hand-request alert settings (owner).
  getTelegramSettings: () => proxyGet<TelegramSettingsDto>(`/api/owner/settings/telegram`),

  updateTelegramSettings: (body: TelegramSettingsUpdateRequest) =>
    proxyJson<TelegramSettingsDto>(`/api/owner/settings/telegram`, 'PUT', body),

  // Twilio SMS alert settings (owner).
  getTwilioSmsSettings: () => proxyGet<TwilioSmsSettingsDto>(`/api/owner/settings/sms`),

  updateTwilioSmsSettings: (body: TwilioSmsSettingsUpdateRequest) =>
    proxyJson<TwilioSmsSettingsDto>(`/api/owner/settings/sms`, 'PUT', body),

  // SMS automations hub (owner): registry list/toggle + the full sent/received activity log.
  listSmsAutomations: () => proxyGet<SmsAutomationSummary[]>(`/api/owner/automations`),

  toggleSmsAutomation: (key: string, enabled: boolean) =>
    proxyVoid(`/api/owner/automations/${encodeURIComponent(key)}`, 'PUT', { enabled }),

  listSmsActivity: (filters: { phoneNumber?: string; direction?: SmsMessageDirection; automationKey?: string; limit?: number }) => {
    const params = new URLSearchParams();
    if (filters.phoneNumber) params.set('phoneNumber', filters.phoneNumber);
    if (filters.direction) params.set('direction', filters.direction);
    if (filters.automationKey) params.set('automationKey', filters.automationKey);
    params.set('limit', String(filters.limit ?? 100));
    return proxyGet<SmsMessageDto[]>(`/api/owner/automations/activity?${params.toString()}`);
  },

  getSmsUnreadCount: () => proxyGet<{ unreadCount: number }>(`/api/owner/automations/activity/unread-count`),

  markSmsMessageRead: (id: number) =>
    proxyVoid(`/api/owner/automations/activity/${id}/read`, 'POST'),

  // Manager conversation view (/admin/messages): grouped-by-phone-number inbox + reply.
  listSmsConversations: () => proxyGet<SmsConversationDto[]>(`/api/owner/automations/activity/conversations`),

  getSmsThread: (phoneNumber: string) =>
    proxyGet<SmsMessageDto[]>(`/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}`),

  sendSmsReply: (phoneNumber: string, body: string) =>
    proxyJson<SmsReplyResult>(`/api/owner/automations/activity/reply`, 'POST', { phoneNumber, body }),

  // Marketing contacts: resolves any lead that never linked to a Square customer through the
  // tracked booking flow (a manager followed up and booked them by phone, or they came back
  // through some other channel) and returns the refreshed list — owner-only.
  syncMarketingContacts: () => proxyJson<MarketingContactsData>(`/api/owner/marketing/contacts/sync`, 'POST', {}),

  // When "Sync appointments" was last actually run — cheap (a single DB row, no Square calls),
  // safe to fetch on every marketing tab's mount.
  getMarketingSyncStatus: () => proxyGet<MarketingSyncStatusData>(`/api/owner/marketing/contacts/sync`),

  // Knowledge Base articles. Reads are role-filtered by the backend; writes/sync are OWNER+MANAGER.
  listKbArticles: () => proxyGet<KbArticle[]>(`/api/kb-articles`),

  getKbArticle: (id: number) => proxyGet<KbArticle>(`/api/kb-articles/${id}`),

  createKbArticle: (body: KbWriteRequest) => proxyJson<KbArticle>(`/api/kb-articles`, 'POST', body),

  updateKbArticle: (id: number, body: KbWriteRequest) =>
    proxyJson<KbArticle>(`/api/kb-articles/${id}`, 'PUT', body),

  deleteKbArticle: (id: number) => proxyVoid(`/api/kb-articles/${id}`, 'DELETE'),

  // Sync one article into the RAG store; returns the updated article (possibly in ERROR).
  syncKbArticle: (id: number) => proxyJson<KbArticle>(`/api/kb-articles/${id}/sync`, 'POST', {}),

  // Bulk sync (backend returns 409 if one is already running). The admin UI drives per-article
  // sync in a loop for live progress; this is the one-shot equivalent.
  syncAllKbArticles: () => proxyJson<KbArticle[]>(`/api/kb-articles/sync-all`, 'POST', {}),

  // Export URLs, not fetch calls — used directly as an <a href> so the browser handles the
  // Content-Disposition download natively (same-origin, session cookie sent automatically).
  kbArticleDownloadUrl: (id: number) => `/api/kb-articles/${id}/download`,
  kbArticleDownloadAllUrl: () => `/api/kb-articles/download-all`,

  // AI drafting via our own Claude endpoint (no paid editor-AI add-on).
  aiDraftKbArticle: (prompt: string, currentBody: string | null) =>
    proxyJson<{ markdown: string }>(`/api/kb-articles/ai-draft`, 'POST', { prompt, currentBody }),

  // AI translate the English body to Russian (keeps customer-facing English intact).
  aiTranslateKbArticle: (body: string) =>
    proxyJson<{ markdown: string }>(`/api/kb-articles/ai-translate`, 'POST', { body }),

  // Translate a short title to Russian (not a full article).
  aiTranslateKbNote: (body: string) =>
    proxyJson<{ markdown: string }>(`/api/kb-articles/ai-translate-note`, 'POST', { body }),

  // SOPs. Reads are audience-filtered by the backend; writes/publish/archive are OWNER; acknowledge
  // is MANAGER/PROVIDER (and the backend checks the caller is in the SOP's audience).
  listSops: () => proxyGet<Sop[]>(`/api/sops`),

  // One SOP, for the shareable-link detail page — same audience rule as listSops.
  getSop: (id: number) => proxyGet<Sop>(`/api/sops/${id}`),

  createSop: (body: SopCreateRequest) => proxyJson<Sop>(`/api/sops`, 'POST', body),

  updateSop: (id: number, body: SopUpdateRequest) => proxyJson<Sop>(`/api/sops/${id}`, 'PUT', body),

  listSopVersions: (id: number) => proxyGet<SopVersion[]>(`/api/sops/${id}/versions`),

  addSopVersion: (id: number, body: string, bodyRu: string | null, changeNote: string | null, changeNoteRu: string | null) =>
    proxyJson<SopVersion>(`/api/sops/${id}/versions`, 'POST', { body, bodyRu, changeNote, changeNoteRu }),

  // AI translate a SOP version body to Russian (keeps customer-facing English intact).
  aiTranslateSop: (body: string) =>
    proxyJson<{ markdown: string }>(`/api/sops/ai-translate`, 'POST', { body }),

  // Draft a short "what changed" note (English) by comparing the previous body to the new one.
  aiSummarizeSopChange: (oldBody: string, newBody: string) =>
    proxyJson<{ markdown: string }>(`/api/sops/ai-summarize-change`, 'POST', { oldBody, newBody }),

  // Translate a short change note to Russian (not a full article).
  aiTranslateSopNote: (body: string) =>
    proxyJson<{ markdown: string }>(`/api/sops/ai-translate-note`, 'POST', { body }),

  publishSopVersion: (id: number, versionId: number) =>
    proxyJson<Sop>(`/api/sops/${id}/versions/${versionId}/publish`, 'POST', {}),

  archiveSop: (id: number) => proxyJson<Sop>(`/api/sops/${id}/archive`, 'POST', {}),

  unarchiveSop: (id: number) => proxyJson<Sop>(`/api/sops/${id}/unarchive`, 'POST', {}),

  sopRoster: (id: number) => proxyGet<SopRosterEntry[]>(`/api/sops/${id}/acknowledgment-status`),

  acknowledgeSop: (id: number) => proxyJson<Sop>(`/api/sops/${id}/acknowledge`, 'POST', {}),

  // SOP → RAG sync (owner). Pushes published SOPs into the assistant corpus.
  listSopRagSync: () => proxyGet<SopSyncItem[]>(`/api/sops/rag-sync`),

  syncSopRag: (id: number) => proxyJson<SopSyncItem>(`/api/sops/${id}/rag-sync`, 'POST', {}),

  syncAllSopsRag: () => proxyJson<SopSyncItem[]>(`/api/sops/rag-sync-all`, 'POST', {}),

  // Export URLs, not fetch calls — used directly as an <a href> so the browser handles the
  // Content-Disposition download natively (same-origin, session cookie sent automatically).
  sopDownloadUrl: (id: number) => `/api/sops/${id}/download`,
  sopDownloadAllUrl: () => `/api/sops/download-all`,

  // The authenticated principal — used by the assistant widget to self-gate by role.
  getMe: () => proxyGet<Me>(`/api/me`),

  // Set the caller's preferred language (owner/manager).
  setLanguage: (language: Language) => proxyVoid(`/api/me/language`, 'POST', { language }),

  // Grounded starter prompts for the assistant's empty state.
  getRagSuggestions: () => proxyGet<StarterSuggestions>(`/api/rag/suggestions`),

  // Regenerate the starter prompts on demand (owner/manager).
  refreshRagSuggestions: () => proxyJson<StarterSuggestions>(`/api/rag/suggestions/refresh`, 'POST', {}),

  // Knowledge-gap requests. Create = owner/manager (from the assistant); manage = owner (/rag/admin).
  createKbRequest: (body: { question: string; note: string | null; target: KbRequestTarget }) =>
    proxyJson<KbRequest>(`/api/rag/requests`, 'POST', body),

  // Translates a gap-report note to Russian, so a Russian-speaking owner can read it directly.
  translateKbRequestNote: (note: string) =>
    proxyJson<{ translated: string }>(`/api/rag/requests/translate-note`, 'POST', { note }),

  listKbRequests: () => proxyGet<KbRequest[]>(`/api/rag/admin/requests`),

  setKbRequestStatus: (id: number, status: KbRequestStatus) =>
    proxyJson<KbRequest>(`/api/rag/admin/requests/${id}/status`, 'POST', { status }),

  deleteKbRequest: (id: number) => proxyVoid(`/api/rag/admin/requests/${id}`, 'DELETE'),

  // Stream a grounded answer token-by-token over SSE. Calls back as events arrive.
  askRagStream: async (
    question: string,
    h: {
      onToken: (text: string) => void;
      onCitations: (citations: RagCitation[]) => void;
      onFollowups: (followups: string[]) => void;
      onDone: (d: { traceRunId: string | null; answered: boolean }) => void;
      onError: (message: string) => void;
    },
  ): Promise<void> => {
    let res: Response;
    try {
      res = await fetch(`/api/rag/ask/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      });
    } catch {
      h.onError('Network error.');
      return;
    }
    if (!res.ok || !res.body) {
      h.onError('The assistant is unavailable right now.');
      return;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep: number;
      // SSE events are separated by a blank line.
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        let event = 'message';
        let data = '';
        for (const line of rawEvent.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) data += line.slice(5).trim();
        }
        if (!data) continue;
        const parsed = JSON.parse(data);
        if (event === 'token') h.onToken(parsed.text as string);
        else if (event === 'citations') h.onCitations(parsed as RagCitation[]);
        else if (event === 'followups') h.onFollowups(parsed as string[]);
        else if (event === 'done') h.onDone(parsed as { traceRunId: string | null; answered: boolean });
        else if (event === 'error') h.onError((parsed.message as string) ?? 'Error');
      }
    }
  },

  // Owner marketing dashboard: variant management + the "hide test data before this date"
  // stats cutoff. All owner-only, enforced server-side. from/to (yyyy-MM-dd, both optional) are
  // the shared marketing period filter (see PeriodFilter) — omitted means "All".
  getMarketingDashboard: (slug: string, sources: Set<TrafficSourceKey>, from?: string, to?: string) => {
    const params = new URLSearchParams({ slug, sources: sourcesParam(sources) });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    return proxyGet<MarketingDashboardData>(`/api/owner/marketing?${params.toString()}`);
  },

  getMarketingPages: () => proxyGet<MarketingLandingPage[]>('/api/owner/marketing/pages'),

  // Client-side (not just serverApi) since Compare mode fetches every other landing page's
  // funnel on demand, after the initial page load. from/to as above.
  getMarketingFunnel: (slug: string, sources: Set<TrafficSourceKey>, from?: string, to?: string) => {
    const params = new URLSearchParams({ slug, sources: sourcesParam(sources) });
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    return proxyGet<FunnelDashboardData[]>(`/api/owner/marketing/funnel?${params.toString()}`);
  },

  // "Analyze Funnel" — owner-only (see the route handler); 404s if ai.funnel-analysis.enabled is
  // off on the backend. Cached server-side by the exact funnel numbers (which differ between
  // ads/all traffic, so the two modes never share a stale cached result), so a repeat click with
  // unchanged data is free. force=true bypasses that cache — the "run again anyway" action. mode
  // stays a plain 'ads'|'all' binary (the AI feature's own, separate contract, not the 5-way
  // TrafficSourceFilter) — callers map their current selection down to whichever of the two is
  // the closer match.
  analyzeFunnel: (slug: string, flowKey: string, mode: 'ads' | 'all' = 'ads', force = false) =>
    proxyJson<FunnelAnalysisResult>(
      `/api/owner/marketing/funnel/analyze?slug=${encodeURIComponent(slug)}&flowKey=${encodeURIComponent(flowKey)}&mode=${mode}${force ? '&force=true' : ''}`,
      'POST',
      {},
    ),

  // Past analyses for this landing page/flow, newest first — powers the history list under the
  // Analyze Funnel button so a prior result stays visible (with its timestamp) without a re-run.
  getFunnelAnalysisHistory: (slug: string, flowKey: string) =>
    proxyGet<FunnelAnalysisResult[]>(
      `/api/owner/marketing/funnel/analyze/history?slug=${encodeURIComponent(slug)}&flowKey=${encodeURIComponent(flowKey)}`,
    ),

  renameMarketingVariant: (variantId: string, name: string) =>
    proxyVoid(`/api/owner/marketing/variants/${variantId}`, 'PATCH', { name }),

  // description: '' clears it; the backend only leaves it untouched when the field is absent
  // entirely, which this always-present-key call never does.
  setMarketingVariantDescription: (variantId: string, description: string) =>
    proxyVoid(`/api/owner/marketing/variants/${variantId}`, 'PATCH', { description }),

  deleteMarketingVariant: (variantId: string) => proxyVoid(`/api/owner/marketing/variants/${variantId}`, 'DELETE'),

  duplicateMarketingVariant: (variantId: string, name: string) =>
    proxyJson<{ variantId: string }>(`/api/owner/marketing/variants/${variantId}/duplicate`, 'POST', { name }),

  setMarketingStatsSince: (slug: string, value: string | null) =>
    proxyVoid(`/api/owner/marketing/stats-since?slug=${encodeURIComponent(slug)}`, 'PUT', { value }),

  // from/to are ISO dates (yyyy-MM-dd); omitting both defaults to month-to-date on the backend.
  // sources omitted defaults to "Ads only" on the backend; the full 5-bucket set (or omitting
  // this param on a call site that always wants everything) counts every contact regardless of
  // channel. slug omitted pools every landing page.
  getMarketingAnalytics: (from?: string, to?: string, sources?: Set<TrafficSourceKey>, slug?: string) => {
    const params = new URLSearchParams();
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (sources) params.set('sources', sourcesParam(sources));
    if (slug) params.set('slug', slug);
    const qs = params.toString();
    return proxyGet<MarketingAnalyticsData>(`/api/owner/marketing/analytics${qs ? `?${qs}` : ''}`);
  },

  // period is 'week'|'month'|'mtd'|'custom'|'all'; from/to/sources/slug follow the same
  // conventions as getMarketingAnalytics above. 'mtd'/'all' ignore from/to ('mtd' is always
  // [1st-of-month, today], 'all' is the backend's own all-time start through today); 'custom'
  // requires both from and to. week/month default to the last 8 weeks/6 months when omitted.
  getMarketingAdsReport: (
    period: 'week' | 'month' | 'mtd' | 'custom' | 'all', from?: string, to?: string, sources?: Set<TrafficSourceKey>, slug?: string,
  ) => {
    const params = new URLSearchParams();
    params.set('period', period);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    if (sources) params.set('sources', sourcesParam(sources));
    if (slug) params.set('slug', slug);
    return proxyGet<MarketingAdsReportData>(`/api/owner/marketing/ads-report?${params.toString()}`);
  },

  // All-time customer lifetime value by acquisition channel for one page — never period-scoped,
  // unlike getMarketingAdsReport above (a customer's LTV spans every visit since first touch).
  getMarketingLtv: (slug?: string) => {
    const qs = slug ? `?slug=${encodeURIComponent(slug)}` : '';
    return proxyGet<MarketingLtvData>(`/api/owner/marketing/ads-report/ltv${qs}`);
  },

  // Records a new ad-spend-entry row for one page and period — never upserts; a corrected
  // re-entry is kept alongside the original so spend history stays auditable.
  createAdSpendEntry: (landingPageSlug: string, periodStart: string, periodEnd: string, amount: number) =>
    proxyJson<AdSpendEntry>('/api/owner/marketing/ads-report/spend', 'POST', { landingPageSlug, periodStart, periodEnd, amount }),

  // Every entered spend row for one page, most recent period first.
  listAdSpendEntries: (slug: string) =>
    proxyGet<AdSpendEntry[]>(`/api/owner/marketing/ads-report/spend?slug=${encodeURIComponent(slug)}`),

  // Edits an existing entry in place — for fixing an outright mistake (wrong amount/dates), not a
  // genuine revision (enter a new row via createAdSpendEntry for that, so history stays auditable).
  updateAdSpendEntry: (id: number, periodStart: string, periodEnd: string, amount: number) =>
    proxyJson<AdSpendEntry>(`/api/owner/marketing/ads-report/spend/${id}`, 'PUT', { periodStart, periodEnd, amount }),

  // Removes an outright mistaken entry (duplicate, wrong amount typed in).
  deleteAdSpendEntry: (id: number) => proxyVoid(`/api/owner/marketing/ads-report/spend/${id}`, 'DELETE'),

  // One Square customer's submission + appointment history — fetched lazily, only when a row on
  // the Ads Report breakdown's Completed/Anticipated lists is expanded.
  getMarketingCustomerHistory: (customerId: string) =>
    proxyGet<MarketingCustomerHistory>(`/api/owner/marketing/ads-report/customer-history?customerId=${encodeURIComponent(customerId)}`),

  // Staff documents (owner-only): contracts/licenses/NDAs per provider or manager, each with a
  // required expiration date. Upload is multipart, same reason as uploadRagDocument above.
  listStaffDocuments: () => proxyGet<StaffDocument[]>(`/api/owner/staff-documents`),

  createStaffDocument: async (params: {
    file: File;
    providerId?: number;
    appUserId?: number;
    documentType: string;
    label?: string;
    expirationDate: string;
  }): Promise<StaffDocument> => {
    const form = new FormData();
    form.append('file', params.file);
    if (params.providerId != null) form.append('providerId', String(params.providerId));
    if (params.appUserId != null) form.append('appUserId', String(params.appUserId));
    form.append('documentType', params.documentType);
    if (params.label) form.append('label', params.label);
    form.append('expirationDate', params.expirationDate);
    const res = await fetch(`/api/owner/staff-documents`, { method: 'POST', body: form });
    if (!res.ok) throw new Error(await extractErrorMessage(res));
    return (await res.json()) as StaffDocument;
  },

  staffDocumentDownloadUrl: (id: number) => `/api/owner/staff-documents/${id}/download`,

  // Provider/manager self-service — the caller's own document only (see StaffDocumentSelfController).
  myStaffDocumentDownloadUrl: (id: number) => `/api/staff-documents/me/${id}/download`,

  // Every field optional — omitted means "leave as-is" (see the backend's own UpdateStaffDocumentRequest).
  updateStaffDocument: (id: number, body: { expirationDate?: string; documentType?: string; label?: string }) =>
    proxyJson<StaffDocument>(`/api/owner/staff-documents/${id}`, 'PATCH', body),

  deleteStaffDocument: (id: number) => proxyVoid(`/api/owner/staff-documents/${id}`, 'DELETE'),
};

// The backend's default Spring error body is {"message": "...", "error": "...", "status": ...} —
// prefer the deliberately-written `message` (e.g. "Can't delete — has recorded activity")
// over the generic HTTP reason phrase, falling back to raw text if the body isn't JSON.
async function extractErrorMessage(res: Response): Promise<string> {
  const text = await res.text();
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed.message === 'string' && parsed.message) return parsed.message;
  } catch {
    // not JSON — fall through to raw text
  }
  return text || `${res.status} ${res.statusText}`;
}

async function proxyVoid(path: string, method: string, body?: unknown): Promise<void> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res));
}

async function proxyGet<T>(path: string): Promise<T> {
  const res = await fetch(path, { cache: 'no-store' });
  if (!res.ok) throw new Error(await extractErrorMessage(res));
  return (await res.json()) as T;
}

async function proxyJson<T>(path: string, method: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await extractErrorMessage(res));
  return (await res.json()) as T;
}
