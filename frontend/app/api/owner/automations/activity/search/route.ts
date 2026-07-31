import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/search — message-content search across every
// conversation, for the manager conversation view's search box. Query string passed straight
// through (just `q`).
export async function GET(req: Request): Promise<Response> {
  const qs = new URL(req.url).search;
  return forwardToBackend(`/api/owner/automations/activity/search${qs}`, 'GET');
}
