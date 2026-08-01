import { cookies } from 'next/headers';

// GET /api/owner/automations/activity/stream — live-update feed for the manager conversation view
// (see MessagesView.tsx). Pipes the backend SSE through UNBUFFERED, same pattern as
// app/api/rag/ask/stream/route.ts: the backend's ReadableStream is returned as-is so "something
// changed" events reach the browser the instant they're broadcast, not batched up behind Next's or
// nginx's own response buffering.
export const dynamic = 'force-dynamic';

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function GET(): Promise<Response> {
  const sid = (await cookies()).get('sid')?.value;
  if (!sid) return new Response('Unauthorized', { status: 401 });

  const res = await fetch(`${BACKEND}/api/owner/automations/activity/stream`, {
    headers: { Cookie: `JSESSIONID=${sid}`, Accept: 'text/event-stream' },
  });

  if (!res.ok || !res.body) {
    return new Response(null, { status: res.status });
  }

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
