import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  return forwardToBackend(`/api/leads/filter-options?${new URL(req.url).searchParams.toString()}`, 'GET');
}
