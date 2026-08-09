import { forwardToBackend } from '../../../../../lib/proxyBackend';

// PUT /api/owner/expenses/categories/{id} — rename a category's label (owner).
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/categories/${encodeURIComponent(id)}`, 'PUT', await req.text());
}

// DELETE /api/owner/expenses/categories/{id} — remove an unused, unprotected category (owner).
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/categories/${encodeURIComponent(id)}`, 'DELETE');
}
