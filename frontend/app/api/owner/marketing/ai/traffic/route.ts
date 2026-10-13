import { forwardToBackend } from '../../../../../lib/proxyBackend';

// GET /api/owner/marketing/ai/traffic?days=N — visitors, leads, bookings and revenue that came from AI assistants.
export const GET = (req: Request) => {
  const days = new URL(req.url).searchParams.get('days');
  return forwardToBackend(`/api/owner/marketing/ai/traffic${days ? `?days=${encodeURIComponent(days)}` : ''}`, 'GET');
};
