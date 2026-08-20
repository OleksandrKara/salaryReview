import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// PUT /api/owner/settings/sms/templates/{key} — save a business's override body for one template.
export async function PUT(req: Request, { params }: { params: Promise<{ key: string }> }): Promise<Response> {
  const { key } = await params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/settings/sms/templates/${encodeURIComponent(key)}`, 'PUT', body || '{}');
}
