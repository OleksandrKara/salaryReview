'use client';

import Link from 'next/link';
import { useState } from 'react';
import LanguageSwitch from '../components/LanguageSwitch';
import type { Language } from '../lib/types';

// Owner/manager admin navigation. On desktop the links sit inline next to the page title; on a phone
// that row overflowed the viewport, so there we collapse them behind a hamburger that opens a dropdown.
// Note: the assistant (/rag) and its admin (/rag/admin) intentionally aren't here — the assistant is
// the floating chat widget now, and owners reach its admin from the widget's "Admin" link.
const LINKS = [
  { href: '/kb', label: 'Knowledge base' },
  { href: '/sops', label: 'SOPs' },
  { href: '/admin/prepaid', label: 'Prepaid' },
  { href: '/admin/owner-customers', label: 'Owner comps' },
  { href: '/admin/redos', label: 'Redos' },
  { href: '/admin/manual-credits', label: 'Manual' },
];

export default function AdminMenu({ isOwner, language }: { isOwner: boolean; language: Language | null }) {
  const [open, setOpen] = useState(false);

  const links = isOwner
    ? [
        { href: '/owner/overview', label: 'Overview' },
        ...LINKS,
        { href: '/sops/admin', label: 'SOPs admin' },
        { href: '/admin/users', label: 'Users' },
      ]
    : LINKS;
  const link = 'text-xs text-zinc-400 hover:text-zinc-600';

  return (
    <>
      {/* Desktop: inline links */}
      <span className="hidden items-center gap-3 sm:flex">
        {links.map((l) => (
          <Link key={l.href} href={l.href} className={link}>{l.label}</Link>
        ))}
        <LanguageSwitch language={language} />
        <a href="/api/logout" className={link}>Log out</a>
      </span>

      {/* Mobile: hamburger + dropdown */}
      <div className="relative sm:hidden">
        <button
          type="button"
          aria-label="Menu"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
          className="-my-1 flex h-8 w-8 items-center justify-center rounded-md text-zinc-500 hover:bg-zinc-100"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <line x1="3" y1="6" x2="21" y2="6" /><line x1="3" y1="12" x2="21" y2="12" /><line x1="3" y1="18" x2="21" y2="18" />
          </svg>
        </button>
        {open && (
          <>
            <div className="fixed inset-0 z-20" onClick={() => setOpen(false)} aria-hidden />
            <div className="absolute right-0 z-30 mt-1 w-44 overflow-hidden rounded-lg bg-white py-1 shadow-lg ring-1 ring-zinc-200">
              {links.map((l) => (
                <Link key={l.href} href={l.href} onClick={() => setOpen(false)} className="block px-4 py-2 text-sm text-zinc-700 hover:bg-zinc-50">{l.label}</Link>
              ))}
              <div className="flex items-center gap-2 border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500">
                <span className="text-zinc-400">Language</span>
                <LanguageSwitch language={language} />
              </div>
              <a href="/api/logout" className="block border-t border-zinc-100 px-4 py-2 text-sm text-zinc-500 hover:bg-zinc-50">Log out</a>
            </div>
          </>
        )}
      </div>
    </>
  );
}
