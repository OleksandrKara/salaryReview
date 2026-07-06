import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/kb-articles/{id}/download — one article's current body as a .md file (owner).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/kb-articles/${encodeURIComponent(id)}/download`, 'GET');
}
