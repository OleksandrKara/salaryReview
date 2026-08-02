import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/expenses/imports/{id}/complete — finalize: write expense_entries rows for every
// categorized, non-excluded, non-duplicate transaction; import -> COMPLETED (owner).
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/expenses/imports/${encodeURIComponent(id)}/complete`, 'POST', await req.text());
}
