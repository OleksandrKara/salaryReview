import { forwardToBackend } from '../../../../../lib/proxyBackend';

// PUT /api/owner/settings/tracking/{hostname} — set/clear one site's Clarity project id.
export async function PUT(req: Request, { params }: { params: Promise<{ hostname: string }> }): Promise<Response> {
  const { hostname } = await params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/settings/tracking/${encodeURIComponent(hostname)}`, 'PUT', body || '{}');
}
