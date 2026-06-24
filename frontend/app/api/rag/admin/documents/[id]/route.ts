import { forwardToBackend } from '../../../../../lib/proxyBackend';

// DELETE /api/rag/admin/documents/{id} — delete a document (cascades chunks/vectors; writes audit).
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/rag/admin/documents/${encodeURIComponent(id)}`, 'DELETE');
}
