import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug');
  const mode = sp.get('mode');
  const query = new URLSearchParams();
  if (slug) query.set('slug', slug);
  if (mode) query.set('mode', mode);
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing${qs ? `?${qs}` : ''}`, 'GET');
};
