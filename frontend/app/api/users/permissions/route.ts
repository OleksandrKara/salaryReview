import { forwardToBackend } from '../../../lib/proxyBackend';

// Per-user permission grants (owner only; the backend enforces it).
export const GET = () => forwardToBackend('/api/users/permissions', 'GET');
export const PUT = async (req: Request) =>
  forwardToBackend('/api/users/permissions', 'PUT', await req.text());
