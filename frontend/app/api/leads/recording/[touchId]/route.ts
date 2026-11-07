import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function GET(_req: Request, ctx: { params: Promise<{ touchId: string }> }): Promise<Response> {
  const { touchId } = await ctx.params;
  return forwardToBackend(`/api/leads/recording/${encodeURIComponent(touchId)}`, 'GET');
}
