import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/{id}/read — idempotent mark-as-read.
export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/owner/automations/activity/${encodeURIComponent(id)}/read`, 'POST', '{}');
}
