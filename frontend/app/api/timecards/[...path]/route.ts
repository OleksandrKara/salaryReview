import { forwardToBackend } from '../../../lib/proxyBackend';

// One pass-through for the whole /api/timecards tree. The backend decides who may call what.
async function handle(req: Request, ctx: { params: Promise<{ path: string[] }> }, method: string): Promise<Response> {
  const { path } = await ctx.params;
  const qs = new URL(req.url).search;
  const target = `/api/timecards/${path.map(encodeURIComponent).join('/')}${qs}`;
  if (method === 'GET') return forwardToBackend(target, 'GET');
  const body = await req.text();
  return forwardToBackend(target, method, body || '{}');
}

export const GET = (req: Request, ctx: { params: Promise<{ path: string[] }> }) => handle(req, ctx, 'GET');
export const POST = (req: Request, ctx: { params: Promise<{ path: string[] }> }) => handle(req, ctx, 'POST');
export const PUT = (req: Request, ctx: { params: Promise<{ path: string[] }> }) => handle(req, ctx, 'PUT');
