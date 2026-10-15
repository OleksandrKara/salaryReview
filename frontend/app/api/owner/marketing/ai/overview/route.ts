import { forwardToBackend, siteParam } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/overview — AI tab read model (ai-visibility-monitoring design.md D5).
export const GET = (req: Request) => forwardToBackend(`/api/owner/marketing/ai/overview${siteParam(req)}`, 'GET');
