import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// SEO AI Advisor — owner-only (see SecurityConfig's /api/owner/** catch-all; ADS_MANAGER's
// read-only marketing access doesn't extend to this write/LLM-call endpoint, same convention as
// the funnel-analysis POST).
export const POST = (req: Request) => {
  const force = new URL(req.url).searchParams.get('force');
  const query = new URLSearchParams();
  if (force) query.set('force', force);
  return forwardToBackend(`/api/owner/marketing/seo/advisor/analyze?${query.toString()}`, 'POST', '{}');
};
