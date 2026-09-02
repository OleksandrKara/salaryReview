import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/seo/tracked-keywords — add a keyword to the local-SEO rank-tracking
// list (owner-only on the backend). No rank data yet (Phase 5) — this just builds the list.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/marketing/seo/tracked-keywords', 'POST', body || '{}');
}
