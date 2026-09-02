import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/tracking — list this business's sites and their Clarity project ids.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/tracking', 'GET');
}
