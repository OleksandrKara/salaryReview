import { forwardToBackend, siteParam } from '../../../../../../lib/proxyBackend';

// DELETE /api/owner/marketing/seo/tracked-keywords/{id} — remove a tracked keyword. Next 16:
// route params are async.
export async function DELETE(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/seo/tracked-keywords/${encodeURIComponent(id)}${siteParam(req)}`, 'DELETE');
}
