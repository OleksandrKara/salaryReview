import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/seo — read the masked SEO monitoring connection.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/seo', 'GET');
}

// PUT /api/owner/settings/seo — connect/reconnect SEO monitoring for this business.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/seo', 'PUT', body || '{}');
}
