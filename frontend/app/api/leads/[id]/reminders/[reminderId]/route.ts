import { forwardToBackend } from '../../../../../lib/proxyBackend';

type Ctx = { params: Promise<{ id: string; reminderId: string }> };

export async function PATCH(req: Request, ctx: Ctx): Promise<Response> {
  const { id, reminderId } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/reminders/${encodeURIComponent(reminderId)}`, 'PATCH', await req.text());
}

export async function DELETE(_req: Request, ctx: Ctx): Promise<Response> {
  const { id, reminderId } = await ctx.params;
  return forwardToBackend(`/api/leads/${encodeURIComponent(id)}/reminders/${encodeURIComponent(reminderId)}`, 'DELETE');
}
