import { forwardToBackend } from '../../../../lib/proxyBackend';

// from/to were silently dropped here — same gap, same fix, as marketing/route.ts (see its own
// comment). The Funnel tab's PeriodFilter picker had no effect on the backend at all.
export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const query = new URLSearchParams();
  for (const key of ['slug', 'sources', 'from', 'to']) {
    const value = sp.get(key);
    if (value) query.set(key, value);
  }
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing/funnel${qs ? `?${qs}` : ''}`, 'GET');
};
