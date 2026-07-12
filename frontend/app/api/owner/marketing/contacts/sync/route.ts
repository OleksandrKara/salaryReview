import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/contacts/sync — owner-only (enforced server-side).
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/contacts/sync', 'POST', '{}');
}
