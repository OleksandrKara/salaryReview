import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST/DELETE /api/owner/automations/activity/conversations/{phoneNumber}/block — "Block number":
// silently stops all future outbound SMS (automated or manual) to this number, see
// TwilioSmsService and MessagesView.tsx's ConversationMenu.
export async function POST(_req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/block`,
    'POST',
    '{}'
  );
}

export async function DELETE(_req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/block`,
    'DELETE'
  );
}
