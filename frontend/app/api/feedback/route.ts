import { forwardToBackend } from '../../lib/proxyBackend';

// Provider submits approve / request-correction on their own month (backend scopes to the caller).
export async function POST(req: Request): Promise<Response> {
  const qs = new URL(req.url).searchParams.toString();
  return forwardToBackend(`/api/settlements/me/feedback?${qs}`, 'POST', await req.text());
}
