import { NextRequest, NextResponse } from 'next/server';

// Gate the app pages behind the owner login. Unauthenticated visitors are sent to /login.
// /login, /api/* (the login/logout/proxy handlers self-check), and static assets are excluded
// via the matcher below.
export function middleware(req: NextRequest) {
  if (req.cookies.get('auth')) return NextResponse.next();
  const url = req.nextUrl.clone();
  url.pathname = '/login';
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ['/', '/reports/:path*', '/providers/:path*', '/periods/:path*'],
};
