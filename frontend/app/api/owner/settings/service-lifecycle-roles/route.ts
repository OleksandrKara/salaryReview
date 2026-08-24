import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/service-lifecycle-roles — this business's role -> Square service mappings.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/service-lifecycle-roles', 'GET');
}

// POST /api/owner/settings/service-lifecycle-roles — add a mapping.
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/settings/service-lifecycle-roles', 'POST', body || '{}');
}
