import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/conversations/{phoneNumber}/read — marks every unread
// inbound message in this phone number's thread read in one call, so the manager conversation
// view's unread badge actually stays cleared (see MessagesView.tsx's openThread).
export async function POST(_req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/read`,
    'POST',
    '{}'
  );
}
