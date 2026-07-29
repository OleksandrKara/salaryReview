import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/automations/activity — full sent+received log, filterable by phone
// number/direction/automation key. Query string passed straight through.
export async function GET(req: Request): Promise<Response> {
  const qs = new URL(req.url).search;
  return forwardToBackend(`/api/owner/automations/activity${qs}`, 'GET');
}
