import { forwardToBackend } from '../../lib/proxyBackend';

// GET /api/me — the authenticated principal ({username, role, providerId, features}). Used by the
// assistant widget to self-gate (show only for OWNER/MANAGER).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/me', 'GET');
}
