'use client';

import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { t } from '../../lib/i18n';
import type { Language } from '../../lib/types';

const TABS = [
  { href: '/admin/manager-time', key: 'scheduleTabSummary' as const },
  { href: '/admin/manager-time/schedule', key: 'scheduleTabSchedule' as const },
];

// A second in-page way to switch between the payroll summary and the daily coverage/anomaly
// timeline — both live under /admin/manager-time, so forwarding the query string keeps the
// owner's current year/month selection (MonthNav) intact across the switch.
export default function ManagerTimeTabs({ language }: { language: Language | null }) {
  const pathname = usePathname();
  const qs = useSearchParams().toString();

  return (
    <div className="mb-5 flex gap-1 border-b border-zinc-200">
      {TABS.map((tab) => {
        const active = pathname === tab.href;
        return (
          <Link
            key={tab.href}
            href={qs ? `${tab.href}?${qs}` : tab.href}
            prefetch={false}
            aria-current={active ? 'page' : undefined}
            className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
              active ? 'border-zinc-900 text-zinc-900' : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            {t(language, tab.key)}
          </Link>
        );
      })}
    </div>
  );
}
