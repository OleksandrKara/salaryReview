import { forwardToBackend } from '../../../lib/proxyBackend';

// PUT /api/sops/{id} — update title/category/audience (not content). OWNER.
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}`, 'PUT', body || '{}');
}
