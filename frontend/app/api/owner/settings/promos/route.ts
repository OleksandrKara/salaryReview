import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/settings/promos — coupon discount amount/minimum-spend per automation.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/settings/promos', 'GET');
}
