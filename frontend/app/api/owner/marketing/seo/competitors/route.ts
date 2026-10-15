import { forwardToBackend, siteParam } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/seo/competitors — list (owner+ads_manager read-only).
export async function GET(req: Request): Promise<Response> {
  return forwardToBackend(`/api/owner/marketing/seo/competitors${siteParam(req)}`, 'GET');
}

// POST /api/owner/marketing/seo/competitors — add a competitor (owner-only on the backend).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend(`/api/owner/marketing/seo/competitors${siteParam(req)}`, 'POST', body || '{}');
}
