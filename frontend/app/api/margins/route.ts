import { forwardToBackend } from '../../lib/proxyBackend';

// The rough cost assumptions behind ROMI; any signed-in user may read, change or clear them.
export const GET = () => forwardToBackend('/api/margins', 'GET');
export async function PUT(req: Request): Promise<Response> {
  return forwardToBackend('/api/margins', 'PUT', await req.text());
}
export const DELETE = () => forwardToBackend('/api/margins', 'DELETE');
