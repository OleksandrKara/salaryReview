import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/intake-forms/${encodeURIComponent(id)}/resend`, 'POST');
}
