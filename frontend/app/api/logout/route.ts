import { cookies } from 'next/headers';

// Clear the auth cookie and bounce to the login page.
export async function GET(req: Request): Promise<Response> {
  (await cookies()).delete('auth');
  return Response.redirect(new URL('/login', req.url), 303);
}
