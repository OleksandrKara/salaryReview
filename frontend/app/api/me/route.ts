import { forwardToBackend } from '../../lib/proxyBackend';

// GET /api/me — the authenticated principal ({username, role, providerId, features}). Used by the
// assistant widget to self-gate (show only for OWNER/MANAGER). Never cached: the session can change
// (sign in/out) within a browser session, so a stale result must not be served.
export const dynamic = 'force-dynamic';

export async function GET(): Promise<Response> {
  return forwardToBackend('/api/me', 'GET');
}
