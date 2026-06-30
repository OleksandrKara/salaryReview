import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/rag/admin/requests — owner list of knowledge-gap requests.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/rag/admin/requests', 'GET');
}
