import { cookies } from 'next/headers';

// End the backend session, clear our cookies, and bounce to the homepage. Uses a relative Location so
// the browser stays on its own origin (in Docker, req.url's host is the container's 0.0.0.0 bind addr).
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function GET(): Promise<Response> {
  const jar = await cookies();
  const sid = jar.get('sid')?.value;
  if (sid) {
    await fetch(`${BACKEND}/api/logout`, {
      method: 'POST',
      headers: { Cookie: `JSESSIONID=${sid}` },
    }).catch(() => {});
  }
  jar.delete('sid');
  jar.delete('role');
  return new Response(null, { status: 303, headers: { Location: '/' } });
}
