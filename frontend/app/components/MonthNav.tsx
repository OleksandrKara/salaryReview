'use client';

import Link from 'next/link';
import { useLinkStatus } from 'next/link';
import { Spinner } from './Spinner';
import { monthName, monthShort } from '../lib/i18n';
import type { Language } from '../lib/types';

// Shown inside a <Link>: a spinner while that navigation is pending. Switching month only changes the
// search params on the same route, so the route-level loading.tsx doesn't re-trigger — this gives the
// user feedback during the (Square-backed) server fetch. prefetch is off so pending reflects the real load.
function NavSpinner() {
  const { pending } = useLinkStatus();
  // Reserve the slot so the label doesn't shift when the spinner appears.
  return (
    <span className="inline-flex w-3.5 justify-center">
      {pending && <Spinner className="h-3.5 w-3.5 text-zinc-500" />}
    </span>
  );
}

type Ym = { year: number; month: number };

export default function MonthNav({ base, year, month, prev, next, language = null }:
  { base: string; year: number; month: number; prev: Ym; next: Ym; language?: Language | null }) {
  const link = 'inline-flex items-center gap-1 text-zinc-500 hover:text-zinc-800';
  return (
    <div className="flex items-center gap-3 text-sm">
      <Link prefetch={false} href={`${base}?year=${prev.year}&month=${prev.month}`} className={link} data-testid="month-nav-prev">
        <NavSpinner /> ← {monthShort(language, prev.month - 1)}
      </Link>
      <span className="font-medium" data-testid="month-nav-label">{monthName(language, month - 1)} {year}</span>
      <Link prefetch={false} href={`${base}?year=${next.year}&month=${next.month}`} className={link} data-testid="month-nav-next">
        {monthShort(language, next.month - 1)} → <NavSpinner />
      </Link>
    </div>
  );
}
