import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/automations/activity/conversations — one row per distinct phone number,
// most-recent-message-first, backing the manager conversation view's contact list.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/automations/activity/conversations', 'GET');
}
