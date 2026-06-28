import { forwardToBackend } from '../../lib/proxyBackend';

// GET /api/kb-articles — list (backend filters by the caller's role).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/kb-articles', 'GET');
}

// POST /api/kb-articles — create (OWNER/MANAGER, enforced by the backend).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/kb-articles', 'POST', body || '{}');
}
