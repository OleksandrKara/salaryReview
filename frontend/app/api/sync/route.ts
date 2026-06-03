import { forwardToBackend } from '../../lib/proxyBackend';

// On-demand "Sync now": busts the backend's Square read cache so the next render pulls fresh.
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/sync', 'POST');
}
