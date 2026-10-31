import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/progress?weeks=N&site=&engine= — weekly progress timeline for the AI tab.
export const GET = (req: Request) => {
  const src = new URL(req.url).searchParams;
  const out = new URLSearchParams();
  for (const k of ['weeks', 'site', 'engine']) {
    const v = src.get(k);
    if (v) out.set(k, v);
  }
  const qs = out.toString();
  return forwardToBackend(`/api/owner/marketing/ai/progress${qs ? `?${qs}` : ''}`, 'GET');
};
