import { forwardToBackend } from '../../../../lib/proxyBackend';

// PATCH /api/owner/staff-documents/{id} (owner) — edit expiration date and/or type/label in place.
export async function PATCH(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/staff-documents/${encodeURIComponent(id)}`, 'PATCH', await req.text());
}

// DELETE /api/owner/staff-documents/{id} (owner).
export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/staff-documents/${encodeURIComponent(id)}`, 'DELETE');
}
