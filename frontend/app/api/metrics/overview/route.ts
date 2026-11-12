import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  const period = new URL(req.url).searchParams.get('period') ?? '7d';
  return forwardToBackend(`/api/metrics/overview?period=${encodeURIComponent(period)}`, 'GET');
}
