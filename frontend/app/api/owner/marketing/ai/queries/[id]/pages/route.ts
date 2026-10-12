import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/queries/{id}/pages — our target page vs cited competitor pages (design.md D10).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/ai/queries/${encodeURIComponent(id)}/pages`, 'GET');
}
