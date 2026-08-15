import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/business — read this business's name/timezone + financial config.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/business', 'GET');
}

// PUT /api/owner/settings/business — update name/timezone/financial config.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/business', 'PUT', body || '{}');
}
