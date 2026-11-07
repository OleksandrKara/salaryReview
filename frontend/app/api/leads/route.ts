import { forwardToBackend } from '../../lib/proxyBackend';

export async function GET(): Promise<Response> {
  return forwardToBackend('/api/leads', 'GET');
}
