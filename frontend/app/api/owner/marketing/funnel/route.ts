import { forwardToBackend } from '../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const slug = new URL(req.url).searchParams.get('slug');
  return forwardToBackend(`/api/owner/marketing/funnel${slug ? `?slug=${encodeURIComponent(slug)}` : ''}`, 'GET');
};
