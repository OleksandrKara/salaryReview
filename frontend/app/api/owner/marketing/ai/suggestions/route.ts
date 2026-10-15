import { forwardToBackend, siteParam } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/suggestions — searches the assistants ran themselves that we don't track yet.
export const GET = (req: Request) => forwardToBackend(`/api/owner/marketing/ai/suggestions${siteParam(req)}`, 'GET');
