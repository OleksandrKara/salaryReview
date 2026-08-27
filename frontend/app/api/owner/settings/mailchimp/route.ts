import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/mailchimp — read the masked Mailchimp config.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/mailchimp', 'GET');
}

// PUT /api/owner/settings/mailchimp — update API key/audience ID/from name/reply-to email.
export async function PUT(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/mailchimp', 'PUT', body || '{}');
}
