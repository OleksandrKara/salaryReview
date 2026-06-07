import { forwardToBackend } from '../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const params = new URLSearchParams();
  for (const key of ['fromYear', 'fromMonth', 'toYear', 'toMonth']) {
    const v = sp.get(key);
    if (v) params.set(key, v);
  }
  const qs = params.toString();
  return forwardToBackend(`/api/owner/overview${qs ? `?${qs}` : ''}`, 'GET');
};
