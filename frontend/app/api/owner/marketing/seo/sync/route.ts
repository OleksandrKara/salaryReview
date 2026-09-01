import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/seo/sync — manual on-demand refresh (owner-only on the backend).
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/seo/sync', 'POST', '{}');
}
