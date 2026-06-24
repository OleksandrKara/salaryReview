import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/rag/ask/feedback  body: { runId: string, helpful: boolean }
// Thumbs up/down on an answer — shipped to LangSmith as a graded run linked to the trace.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/rag/ask/feedback', 'POST', body || '{}');
}
