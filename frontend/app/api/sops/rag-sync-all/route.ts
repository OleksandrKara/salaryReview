import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/sops/rag-sync-all — sync every SOP (owner). 409 if a bulk run is already in progress.
export async function POST(): Promise<Response> {
  return forwardToBackend('/api/sops/rag-sync-all', 'POST', '{}');
}
