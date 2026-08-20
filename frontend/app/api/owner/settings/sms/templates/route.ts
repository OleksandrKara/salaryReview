import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/settings/sms/templates — list all SMS templates with override status.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/sms/templates', 'GET');
}
