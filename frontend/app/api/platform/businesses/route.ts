import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/platform/businesses — list every business (Phase 5.1).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/platform/businesses', 'GET');
}

// POST /api/platform/businesses — create a new business + seed its first owner login.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/platform/businesses', 'POST', body || '{}');
}
