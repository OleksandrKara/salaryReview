import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// GET /api/owner/expenses/imports/{id}/file — the original uploaded CSV, re-downloadable at any
// time regardless of import status (owner).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/imports/${encodeURIComponent(id)}/file`, 'GET');
}
