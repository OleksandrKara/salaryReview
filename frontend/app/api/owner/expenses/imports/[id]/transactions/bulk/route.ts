import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/expenses/imports/{id}/transactions/bulk — bulk-apply one category/exclude
// reason to a list of transaction ids (owner).
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(
    `/api/owner/expenses/imports/${encodeURIComponent(id)}/transactions/bulk`,
    'POST',
    await req.text(),
  );
}
