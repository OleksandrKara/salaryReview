import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/kb-articles/ai-translate-note — translate a short title into Russian (not a full
// article — no Markdown handling), owner/manager.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/kb-articles/ai-translate-note', 'POST', body || '{}');
}
