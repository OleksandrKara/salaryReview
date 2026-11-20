import { forwardToBackend } from '../../../lib/proxyBackend';

export async function GET(req: Request): Promise<Response> {
  const q = new URL(req.url).searchParams;
  const p = new URLSearchParams({ days: q.get('days') ?? '90' });
  const from = q.get('from');
  const to = q.get('to');
  if (from) p.set('from', from);
  if (to) p.set('to', to);
  return forwardToBackend(`/api/leads/lost-report?${p.toString()}`, 'GET');
}
