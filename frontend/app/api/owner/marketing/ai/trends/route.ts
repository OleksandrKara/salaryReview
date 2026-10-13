import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/trends?weeks=N — weekly visibility per engine and share of answers vs competitors.
export const GET = (req: Request) => {
  const weeks = new URL(req.url).searchParams.get('weeks');
  return forwardToBackend(`/api/owner/marketing/ai/trends${weeks ? `?weeks=${encodeURIComponent(weeks)}` : ''}`, 'GET');
};
