import { cookies } from 'next/headers';

// Validate the owner credential against the backend, then store it in an httpOnly cookie so server
// components and the proxy can authenticate. The cookie holds the Basic token (it is the credential),
// so it's httpOnly to keep it out of JS. Phase-1 single-user; per-user accounts come in Phase 2.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function POST(req: Request): Promise<Response> {
  const { username, password } = await req.json().catch(() => ({} as Record<string, string>));
  if (!username || !password) return new Response('Missing credentials', { status: 400 });

  const token = Buffer.from(`${username}:${password}`).toString('base64');
  const check = await fetch(`${BACKEND}/api/me`, { headers: { Authorization: `Basic ${token}` } });
  if (!check.ok) return new Response('Invalid credentials', { status: 401 });

  (await cookies()).set('auth', token, {
    httpOnly: true,
    sameSite: 'lax',
    // The app currently runs over plain HTTP (localhost / Docker), so the cookie must NOT be
    // Secure or the browser drops it. Revisit when deployed behind HTTPS (Phase 2/3).
    secure: false,
    path: '/',
    maxAge: 60 * 60 * 12, // 12h
  });
  return new Response(null, { status: 204 });
}
