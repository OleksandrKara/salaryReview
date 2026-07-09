import { forwardToBackend } from '../../../../../lib/proxyBackend';

// AI funnel analysis — owner-only (see SecurityConfig's /api/owner/** catch-all; ADS_MANAGER's
// read-only marketing access doesn't extend to this write/LLM-call endpoint, same convention as
// every other non-GET marketing action except ad spend).
export const POST = (req: Request) => {
  const sp = new URL(req.url).searchParams;
  const slug = sp.get('slug');
  const flowKey = sp.get('flowKey');
  const query = new URLSearchParams();
  if (slug) query.set('slug', slug);
  if (flowKey) query.set('flowKey', flowKey);
  return forwardToBackend(`/api/owner/marketing/funnel/analyze?${query.toString()}`, 'POST', '{}');
};
