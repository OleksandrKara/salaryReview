import { forwardToBackend } from '../../../lib/proxyBackend';

// from/to were silently dropped here — this route only ever forwarded slug/sources, so the
// Overview tab's PeriodFilter picker had no effect on the backend at all: every period selection
// (2 days, a week, custom range, anything) still returned the same all-time aggregate. Found live
// 2026-08-22 while trying to answer "did conversions actually drop, checked historically" — the
// period filter couldn't be trusted to answer that question because it was never reaching the
// backend. Same fix, same missed params, as marketing/funnel/route.ts.
export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const query = new URLSearchParams();
  for (const key of ['slug', 'sources', 'from', 'to']) {
    const value = sp.get(key);
    if (value) query.set(key, value);
  }
  const qs = query.toString();
  return forwardToBackend(`/api/owner/marketing${qs ? `?${qs}` : ''}`, 'GET');
};
