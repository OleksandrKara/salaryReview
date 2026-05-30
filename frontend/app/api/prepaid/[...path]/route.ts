import { forwardToBackend } from '../../../lib/proxyBackend';

// Catch-all proxy for the per-package prepaid routes:
//   GET    /api/prepaid/{id}/candidates
//   POST   /api/prepaid/{id}/redemptions
//   DELETE /api/prepaid/{id}
//   DELETE /api/prepaid/redemptions/{redemptionId}
// Next 16: route context params are async.
function backendPath(parts: string[]): string {
  return `/api/prepaid/${parts.map(encodeURIComponent).join('/')}`;
}

export async function GET(_req: Request, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  const { path } = await ctx.params;
  return forwardToBackend(backendPath(path), 'GET');
}

export async function POST(req: Request, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  const { path } = await ctx.params;
  return forwardToBackend(backendPath(path), 'POST', await req.text());
}

export async function DELETE(_req: Request, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  const { path } = await ctx.params;
  return forwardToBackend(backendPath(path), 'DELETE');
}
