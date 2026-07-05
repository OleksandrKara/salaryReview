import { forwardToBackend } from '../../../../lib/proxyBackend';

// Sets/clears the "hide stats before this date" cutoff for a landing page (owner only).
export async function PUT(req: Request): Promise<Response> {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug') ?? 'mani';
  return forwardToBackend(`/api/owner/marketing/stats-since?slug=${encodeURIComponent(slug)}`, 'PUT', await req.text());
}
