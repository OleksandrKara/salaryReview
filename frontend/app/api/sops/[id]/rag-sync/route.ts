import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/sops/{id}/rag-sync — sync one SOP into the assistant corpus (owner).
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/rag-sync`, 'POST', '{}');
}
