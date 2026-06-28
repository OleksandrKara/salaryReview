import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/kb-articles/{id}/sync — sync one article into the RAG store.
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/kb-articles/${encodeURIComponent(id)}/sync`, 'POST', '{}');
}
