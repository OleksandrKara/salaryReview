import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/provider-schedule-closure-alert', 'GET');
}

export async function PUT(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/settings/provider-schedule-closure-alert', 'PUT', (await req.text()) || '{}');
}
