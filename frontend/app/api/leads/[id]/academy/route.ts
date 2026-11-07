import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/academy`, 'PUT', await req.text());
}
