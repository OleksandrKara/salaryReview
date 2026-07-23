import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/staff-documents/me/{id}/download — the caller's own stored file (provider/manager
// self-service; ownership is enforced backend-side, see StaffDocumentSelfController).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/staff-documents/me/${encodeURIComponent(id)}/download`, 'GET');
}
