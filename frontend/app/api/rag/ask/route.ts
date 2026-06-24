import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/rag/ask  body: { question: string }
// Manager/owner asks the knowledge assistant; backend returns the grounded, cited answer.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/rag/ask', 'POST', body || '{}');
}
