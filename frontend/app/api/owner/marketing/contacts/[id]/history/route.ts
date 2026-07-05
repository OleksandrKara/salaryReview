import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// Lazy per-contact Square appointment + submission history (owner only). Next 16: route context
// params are async.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/contacts/${id}/history`, 'GET');
}
