import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/queries/approve-all — every suggested question becomes active.
export const POST = () => forwardToBackend('/api/owner/marketing/ai/queries/approve-all', 'POST', '{}');
