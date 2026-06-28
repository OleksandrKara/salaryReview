import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/kb-articles/sync-all — bulk sync; backend returns 409 if one is already running.
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/kb-articles/sync-all', 'POST', '{}');
}
