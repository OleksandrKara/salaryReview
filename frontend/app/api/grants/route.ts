import { forwardToBackend } from '../../lib/proxyBackend';

// Tier grant/revoke (owner/manager). Forwards the session to the backend grants endpoint.
function backendPath(req: Request): string {
  const qs = new URL(req.url).searchParams.toString();
  return `/api/settlements/grants?${qs}`;
}

export const POST = (req: Request) => forwardToBackend(backendPath(req), 'POST');
export const DELETE = (req: Request) => forwardToBackend(backendPath(req), 'DELETE');
