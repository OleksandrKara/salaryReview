import { forwardToBackend } from '../../../lib/proxyBackend';

// Detail includes the decrypted health answers; the backend limits it to owner, manager and the assigned provider.
export async function GET(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/intake-forms/${encodeURIComponent(id)}`, 'GET');
}
