import { forwardToBackend } from '../../../../lib/proxyBackend';

// PUT edits an existing expense entry in place (fixing an outright mistake); DELETE removes one
// (a duplicate or wrong entry). A genuine, auditable revision still belongs as a new row via
// POST on ../route.ts — these are for correcting data-entry mistakes, not history.
export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/${encodeURIComponent(id)}`, 'PUT', await req.text());
}

export async function DELETE(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/${encodeURIComponent(id)}`, 'DELETE');
}
