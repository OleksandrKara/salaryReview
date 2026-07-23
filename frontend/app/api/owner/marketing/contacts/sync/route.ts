import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/contacts/sync — when "Sync appointments" was last run. Read-only, so
// unlike POST below this is open to ADS_MANAGER too (enforced server-side).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/contacts/sync', 'GET');
}

// POST /api/owner/marketing/contacts/sync — owner-only (enforced server-side).
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/contacts/sync', 'POST', '{}');
}
