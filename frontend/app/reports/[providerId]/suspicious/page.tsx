import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import type { SuspiciousBooking } from '../../../lib/types';
import SuspiciousList from './SuspiciousList';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

export default async function SuspiciousPage({
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
  if (me?.role === 'PROVIDER') redirect('/me');
  // me.features comes from /api/me — the AI triage feature flag is read from the backend env
  // (AI_TRIAGE_ENABLED) and piggybacks on the existing me round-trip. Defaults to false if the
  // backend hasn't shipped the field yet (e.g. older /api/me caches).
  const aiTriageEnabled = me?.features?.aiTriageEnabled ?? false;

  const items: SuspiciousBooking[] = await serverApi.listSuspicious(
    year, month, half, Number(providerId),
  );

  const halfLabel = half === 'FIRST' ? '1–15' : '16–end';
  const backHref = `/reports?year=${year}&month=${month}`;

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← Report</Link>
        <h1 className="text-xl font-semibold sm:text-2xl">Suspicious appointments</h1>
        <span className="text-sm text-zinc-500">
          {MONTHS[month - 1]} {year} · {halfLabel}
        </span>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        Appointments that happened with no Square checkout AND no cash note. After review, click{' '}
        <span className="font-medium">Clear</span> to remove from the badge — use{' '}
        <span className="font-medium">Undo</span> to re-flag if you cleared by mistake.
      </p>

      <SuspiciousList initial={items} aiTriageEnabled={aiTriageEnabled} year={year} month={month} />
    </main>
  );
}
