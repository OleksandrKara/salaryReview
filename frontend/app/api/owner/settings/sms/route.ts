import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/sms — read the masked Twilio config.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/sms', 'GET');
}

// PUT /api/owner/settings/sms — update account SID/API key/secret/from number.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/sms', 'PUT', body || '{}');
}
