import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// PATCH /api/owner/expenses/categories/{id}/personal — flag/unflag a category as personal (owner).
export async function PATCH(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/categories/${encodeURIComponent(id)}/personal`, 'PATCH', await req.text());
}
