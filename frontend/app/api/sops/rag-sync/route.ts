import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/sops/rag-sync — owner list of SOPs with their RAG-sync status.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/sops/rag-sync', 'GET');
}
