import { forwardToBackend } from '../../lib/proxyBackend';

// Missed bookings (owner/manager — backend enforces the role). List/create here; delete under ./[id]/route.ts.
export const GET = () => forwardToBackend('/api/missed-bookings', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/missed-bookings', 'POST', await req.text());
