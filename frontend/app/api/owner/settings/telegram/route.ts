import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/telegram — read the masked config.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/telegram', 'GET');
}

// PUT /api/owner/settings/telegram — update bot token/chat id.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/telegram', 'PUT', body || '{}');
}
