import { forwardToBackend } from '../../lib/proxyBackend';

// User management (owner only — backend enforces the role). List/create here; per-user update/delete
// in ./[id]/route.ts.
export const GET = () => forwardToBackend('/api/users', 'GET');
export const POST = async (req: Request) =>
  forwardToBackend('/api/users', 'POST', await req.text());
