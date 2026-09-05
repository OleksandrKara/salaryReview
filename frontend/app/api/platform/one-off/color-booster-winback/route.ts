import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET = dry run, POST = real send — see ColorBoosterWinbackOneOffController's own doc.
export async function GET(req: Request): Promise<Response> {
  const { search } = new URL(req.url);
  return forwardToBackend(`/api/platform/one-off/color-booster-winback${search}`, 'GET');
}

export async function POST(req: Request): Promise<Response> {
  const { search } = new URL(req.url);
  return forwardToBackend(`/api/platform/one-off/color-booster-winback${search}`, 'POST', '{}');
}
