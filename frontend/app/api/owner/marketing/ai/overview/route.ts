import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/overview — AI tab read model (ai-visibility-monitoring design.md D5).
export const GET = () => forwardToBackend('/api/owner/marketing/ai/overview', 'GET');
