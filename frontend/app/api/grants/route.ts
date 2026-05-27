import { cookies } from 'next/headers';

// Same-origin proxy for tier grant/revoke: the browser calls here (cookie sent automatically),
// and the server adds the owner credential from the httpOnly cookie before calling the backend.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

async function forward(req: Request, method: 'POST' | 'DELETE'): Promise<Response> {
  const token = (await cookies()).get('auth')?.value;
  if (!token) return new Response('Unauthorized', { status: 401 });

  const qs = new URL(req.url).searchParams.toString();
  const res = await fetch(`${BACKEND}/api/settlements/grants?${qs}`, {
    method,
    headers: { Authorization: `Basic ${token}` },
  });
  // A 204/empty backend response must not carry a body, or the Response constructor throws.
  const body = await res.text();
  return new Response(body.length ? body : null, { status: res.status });
}

export const POST = (req: Request) => forward(req, 'POST');
export const DELETE = (req: Request) => forward(req, 'DELETE');
