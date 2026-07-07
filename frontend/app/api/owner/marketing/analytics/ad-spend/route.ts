import { forwardToBackend } from '../../../../../lib/proxyBackend';

// Sets this month's manually-entered ad spend (owner or Ads Manager — the backend enforces that).
export async function PUT(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/analytics/ad-spend', 'PUT', await req.text());
}
