import { cookies } from 'next/headers';

// Clear the auth cookie and bounce to the login page. Uses a relative Location so the browser stays
// on its own origin (in Docker, req.url's host is the container's 0.0.0.0 bind address, not localhost).
export async function GET(): Promise<Response> {
  (await cookies()).delete('auth');
  return new Response(null, { status: 303, headers: { Location: '/login' } });
}
