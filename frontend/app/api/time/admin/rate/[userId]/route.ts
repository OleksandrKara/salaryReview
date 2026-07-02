import { forwardToBackend } from '../../../../../lib/proxyBackend';

export async function PUT(req: Request, ctx: { params: Promise<{ userId: string }> }): Promise<Response> {
  const { userId } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/time/admin/rate/${encodeURIComponent(userId)}`, 'PUT', body || '{}');
}
