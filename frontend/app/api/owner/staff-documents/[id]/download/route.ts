import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/staff-documents/{id}/download — the stored file (owner).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/staff-documents/${encodeURIComponent(id)}/download`, 'GET');
}
