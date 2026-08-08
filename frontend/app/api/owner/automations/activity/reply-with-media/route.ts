import { cookies } from 'next/headers';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// POST /api/owner/automations/activity/reply-with-media — multipart file upload, same reason as
// the staff-documents proxy: forwardToBackend forces JSON, so the multipart body (with its
// boundary) is forwarded raw here, preserving the original Content-Type.
export async function POST(req: Request): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const contentType = req.headers.get('content-type') ?? 'application/octet-stream';
  const buf = await req.arrayBuffer();
  const res = await fetch(`${BACKEND}/api/owner/automations/activity/reply-with-media`, {
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
