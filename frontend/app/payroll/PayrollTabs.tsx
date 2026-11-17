'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Language } from '../lib/types';
import { t } from '../lib/i18n';

export default function PayrollTabs({ lang }: { lang: Language }) {
  const path = usePathname();
  const tabs = [
    { href: '/payroll/periods', label: t(lang, 'pbTabPeriods') },
    { href: '/payroll/days', label: t(lang, 'pbTabDays') },
  ];
  return (
    <nav aria-label={t(lang, 'tcPayroll')} className="mb-4 grid grid-cols-2 gap-1 rounded-lg bg-zinc-100 p-1 text-sm">
      {tabs.map((x) => {
        const on = path === x.href;
        return (
          <Link key={x.href} href={x.href} aria-current={on ? 'page' : undefined}
            className={`flex min-h-[44px] items-center justify-center rounded-md px-2 text-center focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-900 ${on ? 'bg-white font-semibold shadow-sm' : 'text-zinc-600'}`}>
            {x.label}
          </Link>
        );
      })}
    </nav>
  );
}
