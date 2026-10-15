import { forwardToBackend, siteParam } from '../../../../../../lib/proxyBackend';

// PUT /api/owner/marketing/seo/competitors/{id} — update the owner-entered GBP fields/active flag.
// DELETE /api/owner/marketing/seo/competitors/{id} — remove a competitor. Next 16: route params
// are async.
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/marketing/seo/competitors/${encodeURIComponent(id)}${siteParam(req)}`, 'PUT', body || '{}');
}

export async function DELETE(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/seo/competitors/${encodeURIComponent(id)}${siteParam(req)}`, 'DELETE');
}
