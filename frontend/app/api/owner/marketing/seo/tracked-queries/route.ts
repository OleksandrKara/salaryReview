import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/seo/tracked-queries — pin a query to track its position over time
// (owner-only on the backend).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/marketing/seo/tracked-queries', 'POST', body || '{}');
}

// DELETE /api/owner/marketing/seo/tracked-queries?query=... — unpin a tracked query.
export async function DELETE(req: Request): Promise<Response> {
  const query = new URL(req.url).searchParams.get('query') ?? '';
  return forwardToBackend(`/api/owner/marketing/seo/tracked-queries?query=${encodeURIComponent(query)}`, 'DELETE');
}
