import { forwardToBackend } from '../../../../lib/proxyBackend';

// DELETE /api/owner/staff-documents/{id} (owner).
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/staff-documents/${encodeURIComponent(id)}`, 'DELETE');
}
