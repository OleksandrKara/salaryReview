import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/runs/{id} — progress of a re-check.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/ai/runs/${encodeURIComponent(id)}`, 'GET');
}
