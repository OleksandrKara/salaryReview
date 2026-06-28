import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/sops/{id}/acknowledgment-status — owner roster.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/acknowledgment-status`, 'GET');
}
