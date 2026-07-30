import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/conversations/{phoneNumber}/contact — the resolved
// marketing.contacts profile for this phone number (name, email, submission/appointment
// history), or a JSON null body if this number never went through the tracked capture flow.
export async function GET(_req: Request, { params }: { params: Promise<{ phoneNumber: string }> }): Promise<Response> {
  const { phoneNumber } = await params;
  return forwardToBackend(`/api/owner/automations/activity/conversations/${encodeURIComponent(phoneNumber)}/contact`, 'GET');
}
