import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/me/language — set the caller's preferred language (owner/manager, enforced by backend).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/me/language', 'POST', body || '{}');
}
