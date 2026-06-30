import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/rag/admin/requests/{id}/status — owner triage (resolve / dismiss / reopen).
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/rag/admin/requests/${encodeURIComponent(id)}/status`, 'POST', body || '{}');
}
