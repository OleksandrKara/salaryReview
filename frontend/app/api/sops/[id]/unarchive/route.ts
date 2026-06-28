import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/sops/{id}/unarchive (OWNER).
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/unarchive`, 'POST', '{}');
}
