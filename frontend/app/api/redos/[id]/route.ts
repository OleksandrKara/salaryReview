import { forwardToBackend } from '../../../lib/proxyBackend';

// PUT (edit) and DELETE /api/redos/{id}. Next 16: route params are async.
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/redos/${encodeURIComponent(id)}`, 'DELETE');
}

export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/redos/${encodeURIComponent(id)}`, 'PUT', await req.text());
}
