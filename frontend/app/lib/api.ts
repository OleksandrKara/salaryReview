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
  Sop,
  SopCreateRequest,
  SopRosterEntry,
  SopUpdateRequest,
  SopSyncItem,
  SopVersion,
  RagAgentConfigDto,
  Me,
  RagAnswer,
  RagCitation,
  RagDocumentSummary,
  StarterSuggestions,
  TriageClassification,
  TriageResult,
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

  // AI drafting via our own Claude endpoint (no paid editor-AI add-on).
  aiDraftKbArticle: (prompt: string, currentBody: string | null) =>
    proxyJson<{ markdown: string }>(`/api/kb-articles/ai-draft`, 'POST', { prompt, currentBody }),

  // SOPs. Reads are audience-filtered by the backend; writes/publish/archive are OWNER; acknowledge
  // is MANAGER/PROVIDER (and the backend checks the caller is in the SOP's audience).
  listSops: () => proxyGet<Sop[]>(`/api/sops`),

  createSop: (body: SopCreateRequest) => proxyJson<Sop>(`/api/sops`, 'POST', body),

  updateSop: (id: number, body: SopUpdateRequest) => proxyJson<Sop>(`/api/sops/${id}`, 'PUT', body),

  listSopVersions: (id: number) => proxyGet<SopVersion[]>(`/api/sops/${id}/versions`),

  addSopVersion: (id: number, body: string) =>
    proxyJson<SopVersion>(`/api/sops/${id}/versions`, 'POST', { body }),

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

  // The authenticated principal — used by the assistant widget to self-gate by role.
  getMe: () => proxyGet<Me>(`/api/me`),

  // Grounded starter prompts for the assistant's empty state.
  getRagSuggestions: () => proxyGet<StarterSuggestions>(`/api/rag/suggestions`),

  // Stream a grounded answer token-by-token over SSE. Calls back as events arrive.
  askRagStream: async (
    question: string,
    h: {
      onToken: (text: string) => void;
      onCitations: (citations: RagCitation[]) => void;
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
        else if (event === 'done') h.onDone(parsed as { traceRunId: string | null; answered: boolean });
        else if (event === 'error') h.onError((parsed.message as string) ?? 'Error');
      }
    }
  },
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
