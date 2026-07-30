import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/conversations/{phoneNumber} — full chronological thread.
export async function GET(_req: Request, { params }: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await params;
  return forwardToBackend(`/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}`, 'GET');
}
