import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/readiness — latest weekly AI-readiness report (AiReadinessService).
export const GET = () => forwardToBackend('/api/owner/marketing/ai/readiness', 'GET');
