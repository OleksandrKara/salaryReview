import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/queries/{id}/history — every check of one question, newest first.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/ai/queries/${encodeURIComponent(id)}/history`, 'GET');
}
