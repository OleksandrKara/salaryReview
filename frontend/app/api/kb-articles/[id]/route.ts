import { forwardToBackend } from '../../../lib/proxyBackend';

type Ctx = { params: Promise<{ id: string }> };

// GET /api/kb-articles/{id}
export async function GET(_req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/kb-articles/${encodeURIComponent(id)}`, 'GET');
}

// PUT /api/kb-articles/{id}
export async function PUT(req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/kb-articles/${encodeURIComponent(id)}`, 'PUT', body || '{}');
}

// DELETE /api/kb-articles/{id}
export async function DELETE(_req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/kb-articles/${encodeURIComponent(id)}`, 'DELETE');
}
