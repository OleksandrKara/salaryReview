import { forwardToBackend } from '../../../../../lib/proxyBackend';

// DELETE /api/owner/settings/service-lifecycle-roles/{id} — remove a mapping.
export async function DELETE(_req: Request, { params }: { params: Promise<{ id: string }> }): Promise<Response> {
  const { id } = await params;
  return forwardToBackend(`/api/owner/settings/service-lifecycle-roles/${encodeURIComponent(id)}`, 'DELETE');
}
