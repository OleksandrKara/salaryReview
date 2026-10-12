import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET/PUT /api/owner/marketing/ai/settings — brand names, own domains, monthly budget, digest switch.
export const GET = () => forwardToBackend('/api/owner/marketing/ai/settings', 'GET');

export async function PUT(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ai/settings', 'PUT', (await req.text()) || '{}');
}
