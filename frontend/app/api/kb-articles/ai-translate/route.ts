import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/kb-articles/ai-translate — translate the English body into Russian (owner/manager).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/kb-articles/ai-translate', 'POST', body || '{}');
}
