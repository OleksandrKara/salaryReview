import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/{id}/reactions — toggles the current staff member's own
// reaction on a message (adds/replaces, or removes if they send the same emoji again).
export async function POST(req: Request, ctx: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/automations/activity/${encodeURIComponent(id)}/reactions`, 'POST', body);
}
