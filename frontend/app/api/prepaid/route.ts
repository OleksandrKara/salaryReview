import { forwardToBackend } from '../../lib/proxyBackend';

// Prepaid packages (owner/manager — backend enforces the role). List/create here; per-package routes
// (delete, candidates, redemptions) under ./[...path]/route.ts.
export const GET = () => forwardToBackend('/api/prepaid', 'GET');
export const POST = async (req: Request) => forwardToBackend('/api/prepaid', 'POST', await req.text());
