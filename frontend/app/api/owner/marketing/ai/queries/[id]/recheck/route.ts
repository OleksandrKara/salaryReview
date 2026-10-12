import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/queries/{id}/recheck — one question on every engine, once a day.
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/ai/queries/${encodeURIComponent(id)}/recheck`, 'POST', '{}');
}
