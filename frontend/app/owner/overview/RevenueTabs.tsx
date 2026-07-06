'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

const TABS = [
  { href: '/owner/overview', label: 'Overview' },
  { href: '/owner/overview/pulse', label: 'Daily Pulse' },
];

// A second, in-page way to switch between Revenue's views — the main nav menu only ever links to
// /owner/overview itself, so without this the daily pulse page would be reachable only by URL.
export default function RevenueTabs() {
  const pathname = usePathname();
  return (
    <div className="mb-6 flex gap-1 border-b border-zinc-200">
      {TABS.map((tab) => {
        const active = pathname === tab.href;
        return (
          <Link
            key={tab.href}
            href={tab.href}
            prefetch={false}
            aria-current={active ? 'page' : undefined}
            className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
              active ? 'border-zinc-900 text-zinc-900' : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            {tab.label}
          </Link>
        );
      })}
    </div>
  );
}
