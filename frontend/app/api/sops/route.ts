import { forwardToBackend } from '../../lib/proxyBackend';

// GET /api/sops — role-filtered list (backend filters by audience).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/sops', 'GET');
}

// POST /api/sops — create a SOP + first draft (OWNER, enforced by the backend).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/sops', 'POST', body || '{}');
}
