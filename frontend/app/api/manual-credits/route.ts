import { forwardToBackend } from '../../lib/proxyBackend';

// Manual service credits (owner/manager — backend enforces the role). List/create here; delete under
// ./[id]/route.ts.
export const GET = () => forwardToBackend('/api/manual-credits', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/manual-credits', 'POST', await req.text());
