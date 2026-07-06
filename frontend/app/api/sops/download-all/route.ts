import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/sops/download-all — every ACTIVE, published SOP zipped into one archive (owner).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/sops/download-all', 'GET');
}
