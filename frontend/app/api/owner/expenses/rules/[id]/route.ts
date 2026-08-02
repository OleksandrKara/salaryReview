import { forwardToBackend } from '../../../../../lib/proxyBackend';

// PUT /api/owner/expenses/rules/{id} — edit a rule's category/keyword/amount-range/active flag (owner).
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/rules/${encodeURIComponent(id)}`, 'PUT', await req.text());
}

// DELETE /api/owner/expenses/rules/{id} — remove a learned rule (owner).
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/rules/${encodeURIComponent(id)}`, 'DELETE');
}
