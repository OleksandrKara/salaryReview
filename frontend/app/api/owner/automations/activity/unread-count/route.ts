import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/unread-count — backs the nav-entry badge (see PageHeader).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/automations/activity/unread-count', 'GET');
}
