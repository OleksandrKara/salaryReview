import { forwardToBackend } from '../../../../../../../../lib/proxyBackend';

// PUT /api/owner/settings/sms/templates/{key}/variants/{index} — save a business's override body
// for one variant slot of one template.
export async function PUT(req: Request, { params }: { params: Promise<{ key: string; index: string }> }): Promise<Response> {
  const { key, index } = await params;
  const body = await req.text();
  return forwardToBackend(
    `/api/owner/settings/sms/templates/${encodeURIComponent(key)}/variants/${encodeURIComponent(index)}`,
    'PUT',
    body || '{}',
  );
}
