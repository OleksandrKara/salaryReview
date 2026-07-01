'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import LanguageSwitch from './LanguageSwitch';
import type { Language, Role } from '../lib/types';

// The one navigation menu, shown in every page header via PageHeader. A single dropdown at all screen
// sizes so it's identical everywhere and copes with the owner's long link list. Links are role-scoped
// to what the edge proxy actually lets each role reach, so no dead links. Language + Log out live in
// the same menu. The assistant (/rag chat) is the floating widget; its admin is reached from there.
type NavLink = { href: string; label: string };

const COMMON: NavLink[] = [
  { href: '/kb', label: 'Knowledge base' },
  { href: '/sops', label: 'SOPs' },
];

function linksFor(role: Role): NavLink[] {
  if (role === 'OWNER') {
    return [
      { href: '/reports', label: 'Salary report' },
      { href: '/owner/overview', label: 'Revenue' },
      { href: '/owner/retention', label: 'Retention' },
      ...COMMON,
      { href: '/admin/prepaid', label: 'Prepaid' },
      { href: '/admin/owner-customers', label: 'Owner comps' },
      { href: '/admin/redos', label: 'Redos' },
      { href: '/admin/manual-credits', label: 'Manual credits' },
      { href: '/sops/admin', label: 'SOPs admin' },
      { href: '/admin/users', label: 'Users' },
    ];
  }
  if (role === 'MANAGER') {
    return [
      { href: '/manager', label: 'Dashboard' },
      { href: '/admin/redos', label: 'Redos' },
      ...COMMON,
    ];
  }
  return [{ href: '/me', label: 'My pay' }, ...COMMON]; // PROVIDER
}

export default function AdminMenu({ role, language }: { role: Role; language: Language | null }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const links = linksFor(role);

  const isActive = (href: string) => pathname === href || pathname.startsWith(href + '/');

  return (
    <div className="relative">
      <button
        type="button"
        aria-label="Menu"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-zinc-500 hover:bg-zinc-100"
      >
        Menu
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polyline points="6 9 12 15 18 9" />
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
                className={`block px-4 py-2 text-sm hover:bg-zinc-50 ${
                  isActive(l.href) ? 'font-semibold text-zinc-900' : 'text-zinc-700'
                }`}
              >
                {l.label}
              </Link>
            ))}
            <div className="flex items-center gap-2 border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500">
              <span className="text-zinc-400">Language</span>
              <LanguageSwitch language={language} />
            </div>
            <a
              href="/api/logout"
              className="block border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500 hover:bg-zinc-50"
            >
              Log out
            </a>
          </div>
        </>
      )}
    </div>
  );
}
