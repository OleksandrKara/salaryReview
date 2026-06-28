import { forwardToBackend } from '../../../../lib/proxyBackend';

type Ctx = { params: Promise<{ id: string }> };

// GET /api/sops/{id}/versions — full version history (OWNER).
export async function GET(_req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/versions`, 'GET');
}

// POST /api/sops/{id}/versions — add a new draft version (OWNER).
export async function POST(req: Request, ctx: Ctx): Promise<Response> {
  const { id } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(`/api/sops/${encodeURIComponent(id)}/versions`, 'POST', body || '{}');
}
