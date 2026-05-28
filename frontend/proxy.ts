import { NextRequest, NextResponse } from 'next/server';

// Next 16 renamed `middleware` → `proxy`. Gates the app pages behind login and routes by role:
//  - no session (`sid`) → /login
//  - PROVIDER → only their own view (/me); kept out of owner/manager pages
//  - OWNER/MANAGER → reports etc.; /me is meaningless for them → /reports
// /login, /api/* (the route handlers self-check), and static assets are excluded via the matcher.
const PROVIDER_HOME = '/me';
const STAFF_HOME = '/reports';
const STAFF_ONLY = ['/reports', '/providers', '/periods', '/admin'];
const OWNER_ONLY = ['/admin'];

function matches(pathname: string, prefixes: string[]) {
  return prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'));
}

export function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;

  if (!req.cookies.get('sid')) return redirect(req, '/login');

  const role = req.cookies.get('role')?.value;
  const isProvider = role === 'PROVIDER';
  const isOwner = role === 'OWNER';
  const home = isProvider ? PROVIDER_HOME : STAFF_HOME;

  // Providers see only their own view; owner-only areas (user management) are off-limits to others.
  if (isProvider && matches(pathname, STAFF_ONLY)) return redirect(req, PROVIDER_HOME);
  if (!isOwner && matches(pathname, OWNER_ONLY)) return redirect(req, home);
  if (!isProvider && pathname === PROVIDER_HOME) return redirect(req, STAFF_HOME);

  return NextResponse.next();
}

function redirect(req: NextRequest, pathname: string) {
  const url = req.nextUrl.clone();
  url.pathname = pathname;
  url.search = '';
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ['/', '/reports/:path*', '/providers/:path*', '/periods/:path*', '/admin/:path*', '/me'],
};
