import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/queries — add a tracked question (max 20 active + suggested).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ai/queries', 'POST', (await req.text()) || '{}');
}
