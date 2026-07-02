import { forwardToBackend } from '../../../lib/proxyBackend';

export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/time/entries', 'POST', body || '{}');
}
