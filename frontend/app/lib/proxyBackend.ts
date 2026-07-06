import 'server-only';

import { cookies } from 'next/headers';

// Shared same-origin proxy for browser → backend calls that need the session. The browser hits our
// route handler (cookies sent automatically); we forward the JSESSIONID from the httpOnly `sid`
// cookie and relay the backend's status/body. Keeps the session out of client-reachable code.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function forwardToBackend(
  backendPath: string,
  method: string,
  body?: string,
): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const headers: Record<string, string> = { Cookie: `JSESSIONID=${sid}` };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const res = await fetch(`${BACKEND}${backendPath}`, { method, headers, body });
  // Binary-safe passthrough — .text() would corrupt a non-UTF-8 body (e.g. a SOP export ZIP).
  // A 204/empty backend response must not carry a body, or the Response constructor throws.
  const bytes = await res.arrayBuffer();
  const outHeaders: Record<string, string> = { 'Content-Type': res.headers.get('Content-Type') ?? 'application/json' };
  const disposition = res.headers.get('Content-Disposition');
  if (disposition) outHeaders['Content-Disposition'] = disposition;
  return new Response(bytes.byteLength ? bytes : null, { status: res.status, headers: outHeaders });
}
