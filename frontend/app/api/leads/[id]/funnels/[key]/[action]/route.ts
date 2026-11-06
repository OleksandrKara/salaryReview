import { forwardToBackend } from '../../../../../../lib/proxyBackend';

export async function POST(req: Request, ctx: { params: Promise<{ id: string; key: string; action: string }> }): Promise<Response> {
  const { id, key, action } = await ctx.params;
  if (action !== 'apply' && action !== 'stop') return new Response('not found', { status: 404 });
  return forwardToBackend(
    `/api/leads/${encodeURIComponent(id)}/funnels/${encodeURIComponent(key)}/${action}`, 'POST', await req.text());
}
