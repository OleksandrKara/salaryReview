import { forwardToBackend } from '../../lib/proxyBackend';

// Redos (owner/manager — backend enforces the role). List/create here; delete under ./[id]/route.ts.
export const GET = () => forwardToBackend('/api/redos', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/redos', 'POST', await req.text());
