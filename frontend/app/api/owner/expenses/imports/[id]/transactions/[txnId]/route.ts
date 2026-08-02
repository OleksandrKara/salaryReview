import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// PATCH /api/owner/expenses/imports/{id}/transactions/{txnId} — set/change a transaction's
// category or exclude reason, optionally remembering it as a merchant rule (owner).
export async function PATCH(
  req: Request,
  ctx: { params: Promise<{ id: string; txnId: string }> },
): Promise<Response> {
  const { id, txnId } = await ctx.params;
  return forwardToBackend(
    `/api/owner/expenses/imports/${encodeURIComponent(id)}/transactions/${encodeURIComponent(txnId)}`,
    'PATCH',
    await req.text(),
  );
}
