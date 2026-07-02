import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function PATCH(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/time/entries/${encodeURIComponent(id)}`, 'PATCH', body || '{}');
}

export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/time/entries/${encodeURIComponent(id)}`, 'DELETE');
}
