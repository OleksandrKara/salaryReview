import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/rag/suggestions — grounded starter prompts for the assistant's empty state
// (topic-grouped; empty when the feature flag is off or the corpus has nothing indexed).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/rag/suggestions', 'GET');
}
