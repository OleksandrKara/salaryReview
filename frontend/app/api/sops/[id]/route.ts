import { forwardToBackend } from '../../../lib/proxyBackend';

type Ctx = { params: Promise<{ id: string }> };

// GET /api/sops/{id} — one SOP for the shareable-link detail page.
export async function GET(_req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}`, 'GET');
}

// PUT /api/sops/{id} — update title/category/audience (not content). OWNER.
export async function PUT(req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}`, 'PUT', body || '{}');
}
