import { cookies } from 'next/headers';
import { forwardToBackend } from '../../../../lib/proxyBackend';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// GET /api/rag/admin/documents — list uploaded documents with chunk/quarantine counts.
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/rag/admin/documents', 'GET');
}

// POST /api/rag/admin/documents — multipart file upload. forwardToBackend forces JSON, so the
// multipart body (with its boundary) is forwarded raw here, preserving the original Content-Type.
export async function POST(req: Request): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const contentType = req.headers.get('content-type') ?? 'application/octet-stream';
  const buf = await req.arrayBuffer();
  const res = await fetch(`${BACKEND}/api/rag/admin/documents`, {
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
