import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/email-domain-health — SPF/DKIM/DMARC/MX check for this business's
// sending domain.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/email-domain-health', 'GET');
}
