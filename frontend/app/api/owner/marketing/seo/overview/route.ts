import { forwardToBackend, siteParam } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/seo/overview?days=N — trend/keyword/CWV/issues read model.
export const GET = (req: Request) => {
  const days = new URL(req.url).searchParams.get('days');
  const qs = days ? `?days=${days}${siteParam(req, '&')}` : siteParam(req);
  return forwardToBackend(`/api/owner/marketing/seo/overview${qs}`, 'GET');
};
