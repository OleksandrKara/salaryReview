import { forwardToBackend } from '../../lib/proxyBackend';

// Owner/family customers (owner/manager — backend enforces the role). List/add here; delete under
// ./[id]/route.ts and the customer picker under ./search/route.ts.
export const GET = () => forwardToBackend('/api/owner-customers', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/owner-customers', 'POST', await req.text());
