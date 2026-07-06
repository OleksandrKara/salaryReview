import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/kb-articles/download-all — every KB article zipped into one archive (owner).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/kb-articles/download-all', 'GET');
}
