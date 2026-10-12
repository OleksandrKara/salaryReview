import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/competitors — add a place AI recommends to the SEO competitor list.
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ai/competitors', 'POST', (await req.text()) || '{}');
}
