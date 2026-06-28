import { cookies } from 'next/headers';

// POST /api/rag/ask/stream — pipes the backend SSE through UNBUFFERED (unlike forwardToBackend,
// which reads the whole body). The backend's ReadableStream is returned as-is so tokens reach the
// browser as they arrive. Dynamic (reads the session cookie).
export const dynamic = 'force-dynamic';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function POST(req: Request): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const body = await req.text();
  const res = await fetch(`${BACKEND}/api/rag/ask/stream`, {
    method: 'POST',
    headers: {
      Cookie: `JSESSIONID=${sid}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: body || '{}',
  });

  if (!res.ok || !res.body) {
    return new Response(null, { status: res.status });
  }

  // Pipe the backend event stream straight to the client.
  return new Response(res.body, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  });
}
