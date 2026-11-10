import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/owner`, 'GET');
}

export async function PUT(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/owner`, 'PUT', await req.text());
}
