import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/contacts/enrich — lazy per-contact appointment/family-name follow-up
// for the Contacts tab's scroll-triggered reveal (see ContactsTable). Read-only despite being a
// POST (needs a body for the contact-id batch); OWNER/ADS_MANAGER, enforced server-side.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/marketing/contacts/enrich', 'POST', body || '{}');
}
