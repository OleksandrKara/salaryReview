import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  const period = new URL(req.url).searchParams.get('period') ?? '7d';
  return forwardToBackend(`/api/metrics/response?period=${encodeURIComponent(period)}`, 'GET');
}
