import { forwardToBackend } from '../../../../../lib/proxyBackend';

export async function GET(_req: Request, ctx: { params: Promise<{ id: string; emailId: string }> }): Promise<Response> {
  const { id, emailId } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/emails/${encodeURIComponent(emailId)}`, 'GET');
}
