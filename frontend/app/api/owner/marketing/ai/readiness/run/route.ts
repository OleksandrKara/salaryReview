import { forwardToBackend } from '../../../../../../lib/proxyBackend';

// POST /api/owner/marketing/ai/readiness/run — run the AI-readiness check now (owner-only on the backend).
export const POST = () => forwardToBackend('/api/owner/marketing/ai/readiness/run', 'POST', '{}');
