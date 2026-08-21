import { forwardToBackend } from '../../../../../../../../../lib/proxyBackend';

// POST /api/owner/settings/sms/templates/{key}/variants/{index}/reset — delete a business's
// override for one variant slot, revert it to the in-code default.
export async function POST(_req: Request, { params }: { params: Promise<{ key: string; index: string }> }): Promise<Response> {
  const { key, index } = await params;
  return forwardToBackend(
    `/api/owner/settings/sms/templates/${encodeURIComponent(key)}/variants/${encodeURIComponent(index)}/reset`,
    'POST',
    '{}',
  );
}
