import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const params = new URLSearchParams();
  for (const key of ['year', 'month']) {
    const v = sp.get(key);
    if (v) params.set(key, v);
  }
  const qs = params.toString();
  return forwardToBackend(`/api/owner/pulse${qs ? `?${qs}` : ''}`, 'GET');
};
