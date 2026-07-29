import { forwardToBackend } from '../../../../lib/proxyBackend';

// PUT /api/owner/automations/{key} — toggle one automation on/off.
export async function PUT(req: Request, { params }: { params: Promise<{ key: string }> }): Promise<Response> {
  const { key } = await params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/automations/${encodeURIComponent(key)}`, 'PUT', body || '{}');
}
