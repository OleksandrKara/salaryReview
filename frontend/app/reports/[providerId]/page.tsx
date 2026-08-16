import Link from 'next/link';
import { serverApi, ApiError } from '../../lib/serverApi';
import type { ProviderDetail } from '../../lib/types';
import ProviderTrace from '../../components/ProviderTrace';
import { SyncBadge } from '../../components/SyncBadge';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

// Next 16: params and searchParams are Promises.
export default async function ProviderDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ providerId: string }>;
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const { providerId } = await params;
  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  const backHref = `/reports?year=${year}&month=${month}`;
  let detail: ProviderDetail;
  try {
    detail = await serverApi.getProviderDetail(year, month, Number(providerId));
  } catch (err) {
    if (err instanceof ApiError && err.code === 'square_not_connected') {
      return (
        <main className="mx-auto max-w-5xl p-4 sm:p-8">
          <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← Report</Link>
          <SetupRequiredNotice
            title="Connect Square to see this provider's detail"
            message="Provider detail traces every line back to real Square bookings, orders, and payments, which needs a Square connection first."
            ctaHref="/owner/settings/square"
            ctaLabel="Connect Square"
          />
        </main>
      );
    }
    throw err;
  }

  return (
    <main className="mx-auto max-w-5xl p-4 sm:p-8">
      <div className="mb-1 flex items-baseline gap-3">
        <Link href={backHref} className="text-sm text-zinc-500 hover:text-zinc-800">← Report</Link>
        <h1 className="text-2xl font-semibold">{detail.name ?? 'Provider'}</h1>
        <span className="text-sm text-zinc-500">{MONTHS[month - 1]} {year}</span>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        Line-by-line trace — gross is the menu price the payout uses; discount and net are what Square
        actually charged. Copy the #salary block per half, or use the unattributed section below to
        chase a payment that looks missing.
      </p>
      <div className="mb-6"><SyncBadge syncedAt={detail.syncedAt} timezone={detail.timezone} /></div>

      {!detail.payout ? (
        <p className="mt-8 text-center text-zinc-400">No activity for this provider this month.</p>
      ) : (
        <ProviderTrace detail={detail} showUnmatched />
      )}
    </main>
  );
}
