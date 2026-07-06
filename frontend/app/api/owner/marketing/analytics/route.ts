import { forwardToBackend } from '../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const from = sp.get('from');
  const to = sp.get('to');
  const query = new URLSearchParams();
  if (from) query.set('from', from);
  if (to) query.set('to', to);
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing/analytics${qs ? `?${qs}` : ''}`, 'GET');
};
