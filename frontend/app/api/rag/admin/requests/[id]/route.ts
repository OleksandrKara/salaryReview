import { forwardToBackend } from '../../../../../lib/proxyBackend';

// DELETE /api/rag/admin/requests/{id} — owner removes a request.
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/rag/admin/requests/${encodeURIComponent(id)}`, 'DELETE');
}
