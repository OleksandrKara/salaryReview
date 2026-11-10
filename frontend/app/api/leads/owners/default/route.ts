import { forwardToBackend } from '../../../../lib/proxyBackend';

export async function PUT(req: Request): Promise<Response> {
  return forwardToBackend('/api/leads/owners/default', 'PUT', await req.text());
}
