import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/expenses/imports/{id} — one import + its transactions grouped by status (owner).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/imports/${encodeURIComponent(id)}`, 'GET');
}
