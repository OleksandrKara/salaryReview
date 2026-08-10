import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/conversations/{phoneNumber}/draft-reply — AI-drafted reply
// suggestion for the manager conversation view's "Generate" button (see MessagesView.tsx). 404s if
// ai.sms-draft.enabled is off on the backend.
export async function POST(_req: Request, ctx: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await ctx.params;
  return forwardToBackend(
    `/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/draft-reply`,
    'POST',
    '{}'
  );
}
