import { forwardToBackend } from '../../lib/proxyBackend';

// Manual settlement adjustments (owner/manager — backend enforces the role). List/create here;
// delete under ./[id]/route.ts.
export const GET = () => forwardToBackend('/api/manual-adjustments', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/manual-adjustments', 'POST', await req.text());
