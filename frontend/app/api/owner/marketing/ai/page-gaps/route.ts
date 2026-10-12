import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/page-gaps — components most competitor pages have and ours lack.
export const GET = () => forwardToBackend('/api/owner/marketing/ai/page-gaps', 'GET');
