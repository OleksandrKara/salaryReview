import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/rag/admin/documents/{id}/approve — approve a PENDING document → run ingestion.
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/rag/admin/documents/${encodeURIComponent(id)}/approve`, 'POST', '{}');
}
