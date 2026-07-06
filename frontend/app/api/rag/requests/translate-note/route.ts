import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/rag/requests/translate-note — translate a gap-report note to Russian.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/rag/requests/translate-note', 'POST', body || '{}');
}
