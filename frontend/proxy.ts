import { NextRequest, NextResponse } from 'next/server';

// Next 16 renamed `middleware` → `proxy`. This is the single edge gate for every authenticated page,
// routing by role to a role-specific home:
//  - no session (`sid`) → `/` (the landing page, with its sign-in modal)
//  - PROVIDER → /me (their pay), plus /kb and /sops
//  - MANAGER → /manager (redos, KB, SOPs, assistant, retention). Managers don't manage salaries, so
//    they're kept out of /reports and the owner admin tools; redos is their one management task, and
//    retention is read-only visibility shared with the owner.
//  - ADS_MANAGER → /owner/marketing only (read-only there too — enforced by the page/API, not here).
//    An external ads contractor role; blocked from every other page this proxy covers, including
//    KB/SOPs and the manager/provider areas, not just the owner-only ones.
//  - OWNER → /reports and everything
//
// The matcher MUST list every authenticated page area. A page left out gets no edge gate and is only
// as protected as its own code. `/`, `/api/*`, and static assets are excluded so the landing page,
// the proxy API routes, and assets stay reachable.
const PROVIDER_HOME = '/me';
const MANAGER_HOME = '/manager';
const OWNER_HOME = '/reports';
const ADS_MANAGER_HOME = '/owner/marketing';

// Owner + manager (providers blocked). Retention is view-only for managers, same data as owners.
// Manual credits are routine payroll bookkeeping, not a salary decision, so managers get it too
// (see SecurityConfig.java's matching backend rule).
const STAFF_ONLY = ['/manager', '/admin/redos', '/owner/retention', '/admin/manual-credits'];
// Owner only (managers and providers blocked). /reports (salary) and the other admin tools live here;
// redos and manual-credits are intentionally absent (gated by STAFF_ONLY instead). Retention is
// carved out of /owner into STAFF_ONLY above, so only /owner/overview stays owner-only here.
const OWNER_ONLY = [
  '/reports', '/owner/overview', '/admin/users', '/admin/prepaid', '/admin/owner-customers',
  '/admin/manager-time', '/admin/documents', '/rag/admin', '/sops/admin',
];
const PROVIDER_AREAS = ['/me']; // /me and /me/* belong to providers
const ADS_MANAGER_AREAS = ['/owner/marketing']; // the only area this role may reach, full stop

function matches(pathname: string, prefixes: string[]) {
  return prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'));
}

export function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;

  if (!req.cookies.get('sid')) return redirect(req, '/');

  const role = req.cookies.get('role')?.value;
  const isProvider = role === 'PROVIDER';
  const isOwner = role === 'OWNER';
  const isAdsManager = role === 'ADS_MANAGER';
  const home = isProvider ? PROVIDER_HOME : isOwner ? OWNER_HOME : isAdsManager ? ADS_MANAGER_HOME : MANAGER_HOME;

  // Ads Manager is scoped to /owner/marketing only — check this first, before the broader
  // owner/staff/provider carve-outs below (which would otherwise also let it through /owner/**).
  if (isAdsManager) return matches(pathname, ADS_MANAGER_AREAS) ? NextResponse.next() : redirect(req, home);

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
