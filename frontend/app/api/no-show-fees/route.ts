import { forwardToBackend } from '../../lib/proxyBackend';

// No-show fees (owner/manager — backend enforces the role). List a month's rows; overrides live under
// ./confirm, ./suppress and ./[bookingId].
export async function GET(req: Request): Promise<Response> {
  const qs = new URL(req.url).searchParams.toString();
  return forwardToBackend(`/api/no-show-fees?${qs}`, 'GET');
}
