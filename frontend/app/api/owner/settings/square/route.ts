import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/square — read the masked Square connection.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/square', 'GET');
}

// PUT /api/owner/settings/square — connect/reconnect Square for this business.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/square', 'PUT', body || '{}');
}
