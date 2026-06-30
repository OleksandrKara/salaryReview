import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/rag/requests — file a knowledge-gap request (owner/manager).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/rag/requests', 'POST', body || '{}');
}
