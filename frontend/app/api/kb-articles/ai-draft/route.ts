import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/kb-articles/ai-draft — Claude-generated markdown for the editor (OWNER/MANAGER).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/kb-articles/ai-draft', 'POST', body || '{}');
}
