import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  const days = new URL(req.url).searchParams.get('days') ?? '90';
  return forwardToBackend(`/api/leads/lost-report?days=${encodeURIComponent(days)}`, 'GET');
}
