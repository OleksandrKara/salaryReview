import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// Duplicate a variant, copying its weight/content under a new name (owner only).
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/marketing/variants/${id}/duplicate`, 'POST', await req.text());
}
