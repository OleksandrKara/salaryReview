import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/rag/admin/config — read the active agent config.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/rag/admin/config', 'GET');
}

// POST /api/rag/admin/config — create a new active config version.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/rag/admin/config', 'POST', body || '{}');
}
