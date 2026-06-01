import { forwardToBackend } from '../../../lib/proxyBackend';

// DELETE /api/owner-customers/{id} — un-mark an owner customer. Next 16: route params are async.
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner-customers/${encodeURIComponent(id)}`, 'DELETE');
}
