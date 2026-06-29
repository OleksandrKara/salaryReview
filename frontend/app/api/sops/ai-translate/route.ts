import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/sops/ai-translate — translate a SOP version body into Russian (owner).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/sops/ai-translate', 'POST', body || '{}');
}
