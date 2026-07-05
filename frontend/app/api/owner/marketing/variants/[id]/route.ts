import { forwardToBackend } from '../../../../../lib/proxyBackend';

// Rename / activate-deactivate a variant (owner only). Next 16: route context params are async.
export async function PATCH(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/variants/${id}`, 'PATCH', await req.text());
}

export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/variants/${id}`, 'DELETE');
}
