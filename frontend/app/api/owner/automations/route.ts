import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/owner/automations — list every automation with its enabled state + 30-day sent count.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/automations', 'GET');
}
