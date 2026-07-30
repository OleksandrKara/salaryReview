'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import LanguageSwitch from './LanguageSwitch';
import { t } from '../lib/i18n';
import type { Language, Role } from '../lib/types';

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
type NavLink = { href: string; key: NavKey };

const COMMON: NavLink[] = [
  { href: '/kb', key: 'kbTitle' },
  { href: '/sops', key: 'mgrSops' },
];

function linksFor(role: Role): NavLink[] {
  if (role === 'OWNER') {
    return [
      { href: '/reports', key: 'navSalaryReport' },
      { href: '/owner/overview', key: 'navRevenue' },
      { href: '/owner/marketing', key: 'navMarketing' },
      { href: '/admin/messages', key: 'navMessages' },
      { href: '/owner/retention', key: 'navRetention' },
      ...COMMON,
      { href: '/admin/prepaid', key: 'navPrepaid' },
      { href: '/admin/owner-customers', key: 'navOwnerComps' },
      { href: '/admin/redos', key: 'mgrRedos' },
      { href: '/admin/manual-adjustments', key: 'navManualAdjustments' },
      { href: '/admin/manager-time', key: 'navManagerTime' },
      { href: '/sops/admin', key: 'sopAdminTitle' },
      { href: '/admin/users', key: 'navUsers' },
      { href: '/admin/documents', key: 'navStaffDocuments' },
      { href: '/owner/settings/telegram', key: 'navTelegramSettings' },
      { href: '/owner/settings/sms', key: 'navSmsSettings' },
    ];
  }
  if (role === 'MANAGER') {
    return [
      { href: '/manager', key: 'navDashboard' },
      { href: '/manager/time', key: 'navMyTime' },
      { href: '/admin/messages', key: 'navMessages' },
      { href: '/admin/redos', key: 'mgrRedos' },
      { href: '/admin/manual-adjustments', key: 'navManualAdjustments' },
      { href: '/my-documents', key: 'navMyDocuments' },
      ...COMMON,
    ];
  }
  if (role === 'ADS_MANAGER') {
    // Read-only marketing access only — no salary/SOP/KB data, so no COMMON links here.
    return [{ href: '/owner/marketing', key: 'navMarketing' }];
  }
  // PROVIDER
  return [{ href: '/me', key: 'navMyPay' }, { href: '/my-documents', key: 'navMyDocuments' }, ...COMMON];
}

export default function AdminMenu({
  role,
  language,
  kbRequestOpenCount = 0,
  smsUnreadCount = 0,
}: {
  role: Role;
  language: Language | null;
  kbRequestOpenCount?: number;
  smsUnreadCount?: number;
}) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const links = linksFor(role);

  // Highlight only the single most specific match — otherwise a nested route like
  // /owner/marketing/contacts would light up both "Marketing" and "Marketing Contacts" at once.
  const bestMatch = links
    .map((l) => l.href)
    .filter((href) => pathname === href || pathname.startsWith(href + '/'))
    .sort((a, b) => b.length - a.length)[0];
  const isActive = (href: string) => href === bestMatch;

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
              className="absolute right-0 z-30 mt-1 w-52 overflow-hidden rounded-lg bg-white py-1 shadow-lg ring-1 ring-zinc-200"
            >
              {links.map((l) => (
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
                  {/* Unread-count badge for the SMS settings page's Activity log and the Messages
                      inbox (same unread count — one shared sms_message log), visible from
                      anywhere in the app — see openspec/changes/sms-automations-hub design.md
                      tasks.md 8.5 and openspec/changes/lead-followup-and-manager-inbox tasks.md 4.3. */}
                  {(l.href === '/owner/settings/sms' || l.href === '/admin/messages') && smsUnreadCount > 0 ? (
                    <span className="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
                      {smsUnreadCount > 99 ? '99+' : smsUnreadCount}
                    </span>
                  ) : null}
                </Link>
              ))}
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
