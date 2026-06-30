import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/rag/suggestions/refresh — regenerate the chat's starter prompts on demand (owner/manager).
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/rag/suggestions/refresh', 'POST', '{}');
}
