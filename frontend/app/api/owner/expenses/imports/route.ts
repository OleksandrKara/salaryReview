import { cookies } from 'next/headers';
import { forwardToBackend } from '../../../../lib/proxyBackend';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// GET /api/owner/expenses/imports — import history, most recent first (owner).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/expenses/imports', 'GET');
}

// POST /api/owner/expenses/imports — multipart CSV upload, same reason as the RAG admin/staff-
// document upload proxies: forwardToBackend forces JSON, so the multipart body (with its boundary)
// is forwarded raw here, preserving the original Content-Type.
export async function POST(req: Request): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const contentType = req.headers.get('content-type') ?? 'application/octet-stream';
  const buf = await req.arrayBuffer();
  const res = await fetch(`${BACKEND}/api/owner/expenses/imports`, {
    method: 'POST',
    headers: { Cookie: `JSESSIONID=${sid}`, 'Content-Type': contentType },
    body: buf,
  });
  const text = await res.text();
  return new Response(text.length ? text : null, {
    status: res.status,
    headers: { 'Content-Type': res.headers.get('Content-Type') ?? 'application/json' },
  });
}
