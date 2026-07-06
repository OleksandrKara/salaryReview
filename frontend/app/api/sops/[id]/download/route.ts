import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/sops/{id}/download — one SOP's current published version as a .md file (owner).
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/download`, 'GET');
}
