import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/events`, 'POST', await req.text());
}
