import { NextRequest, NextResponse } from 'next/server';

// Next 16 renamed `middleware` → `proxy`. This is the single edge gate for every authenticated page,
// routing by role to a role-specific home:
//  - no session (`sid`) → `/` (the landing page, with its sign-in modal)
//  - PROVIDER → /me (their pay), plus /kb and /sops
//  - MANAGER → /manager (redos, KB, SOPs, assistant). Managers don't manage salaries, so they're kept
//    out of /reports and the owner admin tools; redos is their one management task.
//  - OWNER → /reports and everything
//
// The matcher MUST list every authenticated page area. A page left out gets no edge gate and is only
// as protected as its own code. `/`, `/api/*`, and static assets are excluded so the landing page,
// the proxy API routes, and assets stay reachable.
const PROVIDER_HOME = '/me';
const MANAGER_HOME = '/manager';
const OWNER_HOME = '/reports';

// Owner + manager (providers blocked).
const STAFF_ONLY = ['/manager', '/admin/redos'];
// Owner only (managers and providers blocked). /reports (salary) and the other admin tools live here;
// redos is intentionally absent (it's the manager's one task, gated by STAFF_ONLY instead).
const OWNER_ONLY = [
  '/reports', '/owner', '/admin/users', '/admin/prepaid', '/admin/owner-customers',
  '/admin/manual-credits', '/rag/admin', '/sops/admin',
];
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
  const home = isProvider ? PROVIDER_HOME : isOwner ? OWNER_HOME : MANAGER_HOME;

  // Providers see only their own view; owner-only areas are off-limits to everyone else; /me is the
  // provider's space, so staff are sent to their own home.
  if (isProvider && matches(pathname, STAFF_ONLY)) return redirect(req, PROVIDER_HOME);
  if (!isOwner && matches(pathname, OWNER_ONLY)) return redirect(req, home);
  if (!isProvider && matches(pathname, PROVIDER_AREAS)) return redirect(req, home);

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
    '/manager',
    '/manager/:path*',
    '/me',
    '/me/:path*',
  ],
};
