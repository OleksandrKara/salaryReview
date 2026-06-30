import { NextRequest, NextResponse } from 'next/server';

// Next 16 renamed `middleware` → `proxy`. This is the single edge gate for every authenticated page:
//  - no session (`sid`) → `/` (the landing page, with its sign-in modal)
//  - PROVIDER → only their own view (/me, plus /kb and /sops); kept out of owner/manager pages
//  - OWNER/MANAGER → reports etc.; /me is meaningless for them → /reports
//  - owner-only areas (user management, owner overview, assistant admin, SOP authoring) → OWNER only
//
// The matcher MUST list every authenticated page area. A page left out gets no edge gate and is only
// as protected as its own code — `/rag/admin` (a client component with no server guard) was rendering
// its shell to logged-out visitors for exactly that reason. `/`, `/api/*`, and static assets are
// excluded so the landing page, the proxy API routes, and assets stay reachable.
const PROVIDER_HOME = '/me';
const STAFF_HOME = '/reports';
// Owner + manager only (providers blocked). `/kb` and `/sops` are intentionally absent — providers
// read KB articles shared with them and must acknowledge SOPs.
const STAFF_ONLY = ['/reports', '/admin', '/owner', '/rag'];
// Owner only (managers and providers blocked). `/admin/prepaid` etc. stay owner+manager (not listed).
const OWNER_ONLY = ['/admin/users', '/owner', '/rag/admin', '/sops/admin'];
const PROVIDER_AREAS = ['/me']; // /me and /me/* belong to providers

function matches(pathname: string, prefixes: string[]) {
  return prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'));
}

export function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;

  if (!req.cookies.get('sid')) return redirect(req, '/');

  const role = req.cookies.get('role')?.value;
  const isProvider = role === 'PROVIDER';
  const isOwner = role === 'OWNER';
  const home = isProvider ? PROVIDER_HOME : STAFF_HOME;

  // Providers see only their own view; owner-only areas are off-limits to everyone else.
  if (isProvider && matches(pathname, STAFF_ONLY)) return redirect(req, PROVIDER_HOME);
  if (!isOwner && matches(pathname, OWNER_ONLY)) return redirect(req, home);
  if (!isProvider && matches(pathname, PROVIDER_AREAS)) return redirect(req, STAFF_HOME);

  return NextResponse.next();
}

function redirect(req: NextRequest, pathname: string) {
  const url = req.nextUrl.clone();
  url.pathname = pathname;
  url.search = '';
  return NextResponse.redirect(url);
}

export const config = {
  matcher: [
    '/reports/:path*',
    '/admin/:path*',
    '/owner/:path*',
    '/rag/:path*',
    '/sops/:path*',
    '/kb/:path*',
    '/me',
    '/me/:path*',
  ],
};
