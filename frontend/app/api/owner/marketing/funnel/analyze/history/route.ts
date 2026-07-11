import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// Past AI funnel analyses, newest first — a plain GET, so it inherits SecurityConfig's general
// OWNER+ADS_MANAGER read gate on /api/owner/marketing/** (unlike the sibling analyze POST, which
// is owner-only). The frontend only calls this when canAnalyze is true, matching the Analyze
// button's own owner-only gate, but the backend itself doesn't require that extra restriction.
export const GET = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug');
  const flowKey = sp.get('flowKey');
  const query = new URLSearchParams();
  if (slug) query.set('slug', slug);
  if (flowKey) query.set('flowKey', flowKey);
  return forwardToBackend(`/api/owner/marketing/funnel/analyze/history?${query.toString()}`, 'GET');
};
