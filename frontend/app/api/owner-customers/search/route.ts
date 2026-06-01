import { forwardToBackend } from '../../../lib/proxyBackend';

// GET /api/owner-customers/search?q=... — Square customer name search for the add picker. The query
// string is forwarded to the backend (which calls Square).
export async function GET(req: Request): Promise<Response> {
  const q = new URL(req.url).searchParams.get('q') ?? '';
  return forwardToBackend(`/api/owner-customers/search?q=${encodeURIComponent(q)}`, 'GET');
}
