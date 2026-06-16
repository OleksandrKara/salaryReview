import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import type { SuspiciousBooking } from '../../lib/types';
import MySuspiciousList from './MySuspiciousList';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

// Provider's own actionable list: appointments that happened, no Square checkout, and no notes at all.
// If the appointment was paid in cash, the provider adds a `cashew $nn` note in Square and on the next
// load the row drops off automatically. Clearing is owner/manager only — providers see no buttons here.
export default async function MySuspiciousPage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string; half?: 'FIRST' | 'SECOND' }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const year  = Number(sp.year)  || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;
  const half: 'FIRST' | 'SECOND' = sp.half === 'SECOND' ? 'SECOND' : 'FIRST';

  const me = await serverApi.getMe();
  if (me?.role !== 'PROVIDER') redirect('/reports');

  const items: SuspiciousBooking[] = await serverApi.getMySuspicious(year, month, half);

  const halfLabel = half === 'FIRST' ? '1–15' : '16–end';
  const backHref = `/me?year=${year}&month=${month}`;

  return (
    <main className="mx-auto max-w-2xl p-4 sm:p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← My report</Link>
        <h1 className="text-xl font-semibold sm:text-2xl">Appointments needing a note</h1>
        <span className="text-sm text-zinc-500">
          {MONTHS[month - 1]} {year} · {halfLabel}
        </span>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        These appointments happened but Square shows no checkout AND no note. If any was paid in cash,
        open the appointment in Square (click the customer name) and add a{' '}
        <code className="rounded bg-zinc-100 px-1 py-0.5 text-zinc-700">cashew $nn</code>{' '}
        note — your salary will pick it up on next sync. If it was already paid by card on a different
        booking, just ignore it (the salon manager will review and clear it).
      </p>

      <MySuspiciousList items={items} />
    </main>
  );
}
