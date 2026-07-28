import { forwardToBackend } from '../../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug');
  return forwardToBackend(`/api/owner/marketing/ads-report/ltv${slug ? `?slug=${encodeURIComponent(slug)}` : ''}`, 'GET');
};
