import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// PATCH /api/owner/marketing/ai/queries/{id} — edit text/topic/target page/status/order. Next 16:
// route params are async.
export async function PATCH(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/ai/queries/${encodeURIComponent(id)}`, 'PATCH', (await req.text()) || '{}');
}
