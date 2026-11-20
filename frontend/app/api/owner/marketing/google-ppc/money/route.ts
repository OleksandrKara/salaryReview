import { forwardToBackend } from '../../../../../lib/proxyBackend';

export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const query = new URLSearchParams();
  for (const key of ['from', 'to']) {
    const value = sp.get(key);
    if (value) query.set(key, value);
  }
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing/google-ppc/money${qs ? `?${qs}` : ''}`, 'GET');
};
