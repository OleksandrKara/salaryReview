import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/conversations/paged — cursor-paginated conversations list
// (default 10/page) backing the manager conversation view's initial load + "load more on scroll".
// Query string (cursor, limit) passed straight through.
export async function GET(req: Request): Promise<Response> {
  const qs = new URL(req.url).search;
  return forwardToBackend(`/api/owner/automations/activity/conversations/paged${qs}`, 'GET');
}
