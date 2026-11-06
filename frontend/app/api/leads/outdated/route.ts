import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  const p = new URL(req.url).searchParams;
  const qs = new URLSearchParams({ q: p.get('q') ?? '', seg: p.get('seg') ?? 'all', page: p.get('page') ?? '0' });
  return forwardToBackend(`/api/leads/outdated?${qs.toString()}`, 'GET');
}
