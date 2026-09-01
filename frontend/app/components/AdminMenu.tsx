'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import LanguageSwitch from './LanguageSwitch';
import MessagesNotifierIcon from './MessagesNotifierIcon';
import { api } from '../lib/api';
import { t } from '../lib/i18n';
import type { Language, MeBusinessOption, Role } from '../lib/types';

// The one navigation menu, shown in every page header via PageHeader. A single dropdown at all screen
// sizes so it's identical everywhere and copes with the owner's long link list. Links are role-scoped
// to what the edge proxy actually lets each role reach, so no dead links. Language + Log out live in
// the same menu. The assistant (/rag chat) is the floating widget; its admin is reached from there.
//
// Pinned to the true viewport corner (position: fixed) rather than the top of each page's content
// column — on desktop the content column doesn't reach the screen edge, so anchoring to it put the
// button in a different visual spot than on mobile (where the column is nearly full-width). Fixed
// positioning makes it land in the same physical corner everywhere.
type NavKey = Parameters<typeof t>[1];
// subLinks: a link that's itself an expandable cluster of deep links (e.g. Automations' own
// Overview/SMS/Email/Telegram tabs) — one level deeper than a NavGroup, same expand/collapse
// interaction, so every tab of a multi-tab settings page is one tap away from the menu instead of
// landing on the page and hunting for the right tab. Only Automations uses this today; kept
// generic (not hardcoded to that one link) so a future multi-tab page can reuse it.
type NavLink = { href: string; key: NavKey; subLinks?: NavLink[] };
// A named, collapsible cluster of related links (e.g. "Business integrations": Square/SMS/Telegram)
// — folds rarely-touched settings out of the way so the menu opens short, with everything still one
// tap away. See linksFor's own doc for why OWNER is the only role that needs this.
type NavGroup = { key: string; labelKey: NavKey; links: NavLink[] };
type NavSections = { flat: NavLink[]; groups: NavGroup[] };

const COMMON: NavLink[] = [
  { href: '/kb', key: 'kbTitle' },
  { href: '/sops', key: 'mgrSops' },
];

/** OWNER alone gets 20 links — the rest of the app's daily nav (Salary/Revenue/Marketing/
 * Retention/KB/SOPs) plus every back-office tool and settings page. Flattened, that overflowed a
 * phone screen with no way to reach the last few items (found live 2026-08-20). Split into the
 * 6 links an owner actually opens often (flat, always visible) and 4 named, collapsed-by-default
 * groups for everything else — same total reachability, far shorter at rest. Every other role's
 * list is short enough already to stay flat. */
function linksFor(role: Role): NavSections {
  if (role === 'OWNER') {
    return {
      flat: [
        { href: '/reports', key: 'navSalaryReport' },
        { href: '/owner/overview', key: 'navRevenue' },
        { href: '/owner/marketing', key: 'navMarketing' },
        { href: '/owner/retention', key: 'navRetention' },
        ...COMMON,
      ],
      groups: [
        {
          key: 'payroll',
          labelKey: 'navGroupPayroll',
          links: [
            { href: '/admin/prepaid', key: 'navPrepaid' },
            { href: '/admin/owner-customers', key: 'navOwnerComps' },
            { href: '/admin/redos', key: 'mgrRedos' },
            { href: '/admin/manual-adjustments', key: 'navManualAdjustments' },
            { href: '/admin/manager-time', key: 'navManagerTime' },
          ],
        },
        {
          key: 'staff',
          labelKey: 'navGroupStaff',
          links: [
            { href: '/admin/users', key: 'navUsers' },
            { href: '/admin/documents', key: 'navStaffDocuments' },
            { href: '/sops/admin', key: 'sopAdminTitle' },
            { href: '/owner/reviews', key: 'navReviews' },
            { href: '/admin/missed-bookings', key: 'missedBookingsTitle' },
          ],
        },
        {
          key: 'integrations',
          labelKey: 'navGroupIntegrations',
          links: [
            { href: '/owner/settings/square', key: 'navSquareSettings' },
            { href: '/owner/settings/seo', key: 'navSeoSettings' },
            {
              href: '/owner/settings/automations',
              key: 'navAutomationsSettings',
              subLinks: [
                { href: '/owner/settings/automations', key: 'navAutomationsOverview' },
                { href: '/owner/settings/automations?tab=sms', key: 'navAutomationsSms' },
                { href: '/owner/settings/automations?tab=email', key: 'navAutomationsEmail' },
                { href: '/owner/settings/automations?tab=telegram', key: 'navAutomationsTelegram' },
              ],
            },
          ],
        },
        {
          key: 'settings',
          labelKey: 'navGroupSettings',
          links: [
            { href: '/owner/settings/business', key: 'navBusinessSettings' },
            { href: '/owner/settings/businesses', key: 'navBusinesses' },
            { href: '/onboarding', key: 'navOnboarding' },
          ],
        },
      ],
    };
  }
  if (role === 'MANAGER') {
    return {
      flat: [
        { href: '/manager', key: 'navDashboard' },
        { href: '/manager/time', key: 'navMyTime' },
        { href: '/admin/redos', key: 'mgrRedos' },
        { href: '/admin/missed-bookings', key: 'missedBookingsTitle' },
        { href: '/admin/manual-adjustments', key: 'navManualAdjustments' },
        { href: '/my-documents', key: 'navMyDocuments' },
        ...COMMON,
      ],
      groups: [],
    };
  }
  if (role === 'ADS_MANAGER') {
    // Read-only marketing access only — no salary/SOP/KB data, so no COMMON links here.
    return { flat: [{ href: '/owner/marketing', key: 'navMarketing' }], groups: [] };
  }
  // PROVIDER
  return {
    flat: [{ href: '/me', key: 'navMyPay' }, { href: '/my-documents', key: 'navMyDocuments' }, ...COMMON],
    groups: [],
  };
}

export default function AdminMenu({
  role,
  language,
  kbRequestOpenCount = 0,
  smsUnreadCount = 0,
  activeBusinessId,
  businesses,
}: {
  role: Role;
  language: Language | null;
  kbRequestOpenCount?: number;
  smsUnreadCount?: number;
  // Phase 6.1/6.2 (design.md D12) — undefined (a caller that hasn't been updated yet) renders
  // no switcher row at all, same as before this feature existed.
  activeBusinessId?: number;
  businesses?: MeBusinessOption[];
}) {
  const pathname = usePathname();
  // /owner/settings/businesses (platform_admin only, per PlatformBusinessController) 403s for any
  // other OWNER — found live 2026-08-18 for AK PMU's owner, who saw the link and got a raw crash on
  // click. Same signal the switcher dropdown already uses: a non-platform-admin's `businesses`
  // always has exactly one entry (their own real membership) today, so >1 is platform_admin.
  const isPlatformAdmin = (businesses?.length ?? 0) > 1;
  // seo-monitoring-dashboard design.md D6: hidden entirely for a business that hasn't turned the
  // feature on. AdminMenu (unlike PageHeader) is already 'use client' and renders on every
  // authenticated page regardless of whether that page's own PageHeader call already fetched `me`
  // (many pass role/language straight through without the full features object) — a small
  // self-contained fetch here, defaulting to hidden until it resolves, avoids threading a new prop
  // through every PageHeader call site in the app. Same tradeoff MarketingTabs.tsx already made for
  // its own SEO tab.
  const [seoMonitoringEnabled, setSeoMonitoringEnabled] = useState(false);
  useEffect(() => {
    if (role !== 'OWNER') return;
    let cancelled = false;
    api.getMe().then((me) => { if (!cancelled) setSeoMonitoringEnabled(me.features.seoMonitoringEnabled); }).catch(() => {});
    return () => { cancelled = true; };
  }, [role]);
  const sections = linksFor(role);
  const groups = sections.groups
    .map((g) => ({
      ...g,
      links: g.links
        .filter((l) => l.href !== '/owner/settings/businesses' || isPlatformAdmin)
        .filter((l) => l.href !== '/owner/settings/seo' || seoMonitoringEnabled),
    }))
    .filter((g) => g.links.length > 0);
  const allLinks = [...sections.flat, ...groups.flatMap((g) => g.links), ...groups.flatMap((g) => g.links.flatMap((l) => l.subLinks ?? []))];

  // Highlight only the single most specific match — otherwise a nested route like
  // /owner/marketing/contacts would light up both "Marketing" and "Marketing Contacts" at once.
  const bestMatch = allLinks
    .map((l) => l.href)
    .filter((href) => pathname === href || pathname.startsWith(href + '/'))
    .sort((a, b) => b.length - a.length)[0];
  const isActive = (href: string) => href === bestMatch;
  const activeGroupKey = groups.find((g) => g.links.some((l) => l.href === bestMatch))?.key;

  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  // Which collapsible groups (see linksFor) are expanded — collapsed by default, except whichever
  // one holds the current page (so landing on e.g. /owner/settings/square and opening the menu
  // doesn't hide the very link that's active behind a collapsed "Business integrations" header).
  // Lazy-initialized once at mount from the page you land on, not kept in sync afterward — the
  // menu closes on every link click, so a fresh mount is exactly when this matters.
  const activeSubLinkParent = groups.flatMap((g) => g.links).find((l) => l.subLinks && l.href === bestMatch)?.href;
  const [openGroups, setOpenGroups] = useState<Set<string>>(() => {
    const init = new Set<string>();
    if (activeGroupKey) init.add(activeGroupKey);
    if (activeSubLinkParent) init.add(activeSubLinkParent);
    return init;
  });

  // Bottom fade cue for the scrollable link list — expanding a group can push its own links past
  // the max-height cap with no native scrollbar visible at rest on mobile, which otherwise looks
  // like the group only has one item instead of hinting there's more below (found live
  // 2026-08-20). A ResizeObserver, not a dependency on openGroups, so it reacts to the list's
  // actual rendered height regardless of what changed it (a group toggle, a language switch that
  // changes label lengths, ...).
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [canScrollDown, setCanScrollDown] = useState(false);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const update = () => setCanScrollDown(el.scrollHeight - el.scrollTop - el.clientHeight > 4);
    update();
    el.addEventListener('scroll', update);
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
    };
  }, [open]);

  function toggleGroup(key: string) {
    setOpenGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  async function switchTo(businessId: number) {
    if (businessId === activeBusinessId) return;
    setSwitching(true);
    try {
      await api.switchBusiness(businessId);
      // Every server-rendered page (settings, reports, ...) needs to re-fetch under the new
      // business context — a client-side refresh() won't re-run their server components' data
      // fetches reliably here, so a full reload is the simple, correct choice.
      window.location.reload();
    } catch {
      setSwitching(false);
    }
  }

  return (
    // Above the assistant widget's z-50 (button + open panel) — otherwise, on a short viewport,
    // an open chat panel (h-[32rem] anchored bottom-right) can reach up far enough to overlap this
    // corner and, being on top, visually hide the menu/dropdown entirely.
    <div className="fixed right-4 top-4 z-[60] flex items-center gap-2">
      {role === 'OWNER' ? (
        <Link
          href="/rag/admin#kb-requests"
          aria-label={
            kbRequestOpenCount > 0
              ? `Knowledge requests — ${kbRequestOpenCount} awaiting review`
              : 'Knowledge requests'
          }
          title="Knowledge requests"
          className="relative flex h-9 w-9 items-center justify-center rounded-full bg-white text-zinc-500 shadow-sm ring-1 ring-zinc-200 hover:bg-zinc-50 hover:text-zinc-700"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6" /><path d="M9 15h6" /><path d="M9 11h6" />
          </svg>
          {kbRequestOpenCount > 0 ? (
            <span className="absolute -right-1 -top-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
              {kbRequestOpenCount > 99 ? '99+' : kbRequestOpenCount}
            </span>
          ) : null}
        </Link>
      ) : null}

      {role === 'OWNER' || role === 'MANAGER' ? (
        <MessagesNotifierIcon initialUnreadCount={smsUnreadCount} />
      ) : null}

      <div className="relative">
        <button
          type="button"
          aria-label="Menu"
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
          className="flex h-9 w-9 items-center justify-center rounded-full bg-white text-zinc-500 shadow-sm ring-1 ring-zinc-200 hover:bg-zinc-50 hover:text-zinc-700"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.25" strokeLinecap="round" aria-hidden>
            <line x1="4" y1="6" x2="20" y2="6" /><line x1="4" y1="12" x2="20" y2="12" /><line x1="4" y1="18" x2="20" y2="18" />
          </svg>
        </button>

        {open && (
          <>
            <div className="fixed inset-0 z-20" onClick={() => setOpen(false)} aria-hidden />
            <div
              role="menu"
              className="absolute right-0 z-30 mt-1 flex w-60 flex-col overflow-hidden rounded-lg bg-white shadow-lg ring-1 ring-zinc-200"
            >
              {/* The links themselves scroll independently, capped well under viewport height, so
                  the business switcher / language / log out footer below always stays reachable
                  without having to scroll past every link first — and nothing is ever cut off
                  below the screen edge the way an unbounded list was (found live 2026-08-20). */}
              <div className="relative">
              <div ref={scrollRef} className="max-h-[60vh] overflow-y-auto py-1">
                {sections.flat.map((l) => (
                  <Link
                    key={l.href}
                    href={l.href}
                    onClick={() => setOpen(false)}
                    aria-current={isActive(l.href) ? 'page' : undefined}
                    className={`flex items-center justify-between gap-2 px-4 py-2 text-sm hover:bg-zinc-50 ${
                      isActive(l.href) ? 'font-semibold text-zinc-900' : 'text-zinc-700'
                    }`}
                  >
                    {t(language, l.key)}
                  </Link>
                ))}
                {groups.map((g) => {
                  const expanded = openGroups.has(g.key);
                  return (
                    <div key={g.key} className="border-t border-zinc-100">
                      <button
                        type="button"
                        onClick={() => toggleGroup(g.key)}
                        aria-expanded={expanded}
                        className="flex w-full items-center justify-between gap-2 px-4 py-2 text-left text-sm font-medium text-zinc-500 hover:bg-zinc-50"
                      >
                        {t(language, g.labelKey)}
                        <svg
                          width="12"
                          height="12"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2.5"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          aria-hidden
                          className={`shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`}
                        >
                          <polyline points="6 9 12 15 18 9" />
                        </svg>
                      </button>
                      {expanded && (
                        <div className="bg-zinc-50 pb-1">
                          {g.links.map((l) => {
                            if (!l.subLinks || l.subLinks.length === 0) {
                              return (
                                <Link
                                  key={l.href}
                                  href={l.href}
                                  onClick={() => setOpen(false)}
                                  aria-current={isActive(l.href) ? 'page' : undefined}
                                  className={`flex items-center justify-between gap-2 py-2 pl-7 pr-4 text-sm hover:bg-zinc-100 ${
                                    isActive(l.href) ? 'font-semibold text-zinc-900' : 'text-zinc-600'
                                  }`}
                                >
                                  {t(language, l.key)}
                                </Link>
                              );
                            }
                            // A link that's itself an expandable cluster (see NavLink.subLinks) —
                            // every tab of a multi-tab page (e.g. Automations' Overview/SMS/Email/
                            // Telegram) reachable in one more tap, not just "land on the page and
                            // hunt for the right tab yourself".
                            const subExpanded = openGroups.has(l.href);
                            return (
                              <div key={l.href}>
                                <button
                                  type="button"
                                  onClick={() => toggleGroup(l.href)}
                                  aria-expanded={subExpanded}
                                  className={`flex w-full items-center justify-between gap-2 py-2 pl-7 pr-4 text-left text-sm hover:bg-zinc-100 ${
                                    isActive(l.href) ? 'font-semibold text-zinc-900' : 'text-zinc-600'
                                  }`}
                                >
                                  {t(language, l.key)}
                                  <svg
                                    width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden
                                    className={`shrink-0 transition-transform ${subExpanded ? 'rotate-180' : ''}`}
                                  >
                                    <polyline points="6 9 12 15 18 9" />
                                  </svg>
                                </button>
                                {subExpanded && (
                                  <div className="bg-zinc-100/70">
                                    {l.subLinks.map((sl) => (
                                      <Link
                                        key={sl.href}
                                        href={sl.href}
                                        onClick={() => setOpen(false)}
                                        className="block py-1.5 pl-11 pr-4 text-sm text-zinc-600 hover:bg-zinc-100"
                                      >
                                        {t(language, sl.key)}
                                      </Link>
                                    ))}
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
              {/* An inset shadow, not a color gradient — the menu background is plain white, so a
                  white-to-transparent fade was invisible against it. A shadow reads as "there's a
                  drop-off here" regardless of what's rendered right above it. */}
              <div
                aria-hidden
                className="pointer-events-none absolute inset-x-0 bottom-0 h-3 transition-opacity"
                style={{ opacity: canScrollDown ? 1 : 0, boxShadow: 'inset 0 -10px 8px -8px rgba(0,0,0,0.18)' }}
              />
              </div>
              {businesses && businesses.length > 1 ? (
                <div className="flex items-center gap-2 border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500">
                  <span className="text-zinc-400">{t(language, 'navBusiness')}</span>
                  <select
                    value={activeBusinessId}
                    disabled={switching}
                    onChange={(e) => switchTo(Number(e.target.value))}
                    className="flex-1 rounded border border-zinc-200 bg-white px-2 py-1 text-sm text-zinc-700 disabled:opacity-50"
                  >
                    {businesses.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.name}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
              <div className="flex items-center gap-2 border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500">
                <span className="text-zinc-400">{t(language, 'navLanguage')}</span>
                <LanguageSwitch language={language} />
              </div>
              <a
                href="/api/logout"
                className="block border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500 hover:bg-zinc-50"
              >
                {t(language, 'logout')}
              </a>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
