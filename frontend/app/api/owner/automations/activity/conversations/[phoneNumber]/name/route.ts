import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

export async function PUT(req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/name`,
    'PUT',
    await req.text()
  );
}
