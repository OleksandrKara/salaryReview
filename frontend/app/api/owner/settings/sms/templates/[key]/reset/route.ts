import { forwardToBackend } from '../../../../../../../lib/proxyBackend';

// POST /api/owner/settings/sms/templates/{key}/reset — delete a business's override, revert to default.
export async function POST(_req: Request, { params }: { params: Promise<{ key: string }> }): Promise<Response> {
  const { key } = await params;
  return forwardToBackend(`/api/owner/settings/sms/templates/${encodeURIComponent(key)}/reset`, 'POST', '{}');
}
