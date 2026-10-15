import { forwardToBackend, siteParam } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/seo/sync — manual on-demand refresh (owner-only on the backend).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend(`/api/owner/marketing/seo/sync${siteParam(req)}`, 'POST', '{}');
}
