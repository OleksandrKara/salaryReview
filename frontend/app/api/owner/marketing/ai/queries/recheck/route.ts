import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/queries/recheck {ids} — re-check the selected questions in one run.
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ai/queries/recheck', 'POST', (await req.text()) || '{}');
}
