import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/conversations/{phoneNumber}/summary — single-conversation
// refresh, used to update just the one conversation a live SSE event or a just-sent reply
// touched, instead of re-fetching (and truncating) an already-scrolled-open paginated list.
export async function GET(_req: Request, { params }: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await params;
  return forwardToBackend(`/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/summary`, 'GET');
}
