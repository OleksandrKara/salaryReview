import { forwardToBackend } from '../../../lib/proxyBackend';

// POST /api/sops/ai-summarize-change — draft a short "what changed" note (English), owner only.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/sops/ai-summarize-change', 'POST', body || '{}');
}
