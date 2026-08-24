import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/settings/service-lifecycle-roles/search?q=... — live Square catalog search, so
// the owner picks a real service instead of typing a raw id. Query string passed straight through.
export async function GET(req: Request): Promise<Response> {
  const qs = new URL(req.url).search;
  return forwardToBackend(`/api/owner/settings/service-lifecycle-roles/search${qs}`, 'GET');
}
