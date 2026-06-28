import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/sops/{id}/versions/{versionId}/publish — make a draft version live (OWNER).
export async function POST(
  _req: Request,
  ctx: { params: Promise<{ id: string; versionId: string }> },
): Promise<Response> {
  const { id, versionId } = await ctx.params;
  return forwardToBackend(
    `/api/sops/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/publish`,
    'POST',
    '{}',
  );
}
