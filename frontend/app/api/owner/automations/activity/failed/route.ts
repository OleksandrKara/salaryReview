import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/failed?days=N — outbound texts that did not reach the client.
export async function GET(req: Request): Promise<Response> {
  const days = new URL(req.url).searchParams.get('days') ?? '14';
  return forwardToBackend(`/api/owner/automations/activity/failed?days=${encodeURIComponent(days)}`, 'GET');
}
