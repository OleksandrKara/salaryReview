import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import type { CancelledAppointment } from '../../../lib/types';
import CancelledList from './CancelledList';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

export default async function CancellationsPage({
  params,
  searchParams,
}: {
  params: Promise<{ providerId: string }>;
  searchParams: Promise<{ year?: string; month?: string; half?: 'FIRST' | 'SECOND' }>;
}) {
  const { providerId } = await params;
  const sp = await searchParams;
  const now = new Date();
  const year  = Number(sp.year)  || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;
  const half: 'FIRST' | 'SECOND' = sp.half === 'SECOND' ? 'SECOND' : 'FIRST';

  const me = await serverApi.getMe();
  // Owner-only review surface (managers don't manage salaries / cancellations review).
  if (me?.role !== 'OWNER') redirect('/reports');

  const items: CancelledAppointment[] = await serverApi.listCancellations(
    year, month, half, Number(providerId),
  );

  const halfLabel = half === 'FIRST' ? '1–15' : '16–end';
  const backHref = `/reports?year=${year}&month=${month}`;

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← Report</Link>
        <h1 className="text-xl font-semibold sm:text-2xl">Cancelled appointments</h1>
        <span className="text-sm text-zinc-500">
          {MONTHS[month - 1]} {year} · {halfLabel}
        </span>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        Appointments the salon <span className="font-medium">cancelled after their start time</span> for
        this provider — the slot had already come, so it&apos;s worth a look. Advance cancellations and any
        we charged a cancellation fee on are hidden. Check the cameras to confirm nothing was done and no
        cash was taken, then click <span className="font-medium">Clear</span> to remove it — use{' '}
        <span className="font-medium">Undo</span> to re-flag. This never blocks the provider&apos;s salary.
      </p>

      <CancelledList initial={items} />
    </main>
  );
}
