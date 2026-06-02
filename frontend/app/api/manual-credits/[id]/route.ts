import { forwardToBackend } from '../../../lib/proxyBackend';

// DELETE /api/manual-credits/{id}. Next 16: route params are async.
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/manual-credits/${encodeURIComponent(id)}`, 'DELETE');
}
