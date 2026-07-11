import { forwardToBackend } from '../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug');
  const sources = sp.get('sources');
  const query = new URLSearchParams();
  if (slug) query.set('slug', slug);
  if (sources) query.set('sources', sources);
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing/funnel${qs ? `?${qs}` : ''}`, 'GET');
};
