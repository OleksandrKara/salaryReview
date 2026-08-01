import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/conversations/{phoneNumber}/unread — "mark as unread", a
// manual reminder flag on the conversation (see MessagesView.tsx's ConversationMenu).
export async function POST(_req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/unread`,
    'POST',
    '{}'
  );
}
