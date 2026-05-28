import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import ProviderTrace from '../components/ProviderTrace';
import SettlementFeedbackForm from './SettlementFeedbackForm';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function shift(year: number, month: number, by: number) {
  const idx = (month - 1) + by;
  return { year: year + Math.floor(idx / 12), month: ((idx % 12) + 12) % 12 + 1 };
}

// Provider's read-only view of their own month: summary, a line-by-line breakdown (appointments,
// discounts, cash notes) for tracing their numbers, the copy-pasteable #salary per half, and
// approve / request-correction.
export default async function MyReportPage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  const detail = await serverApi.getMyDetail(year, month);
  const me = detail.payout;
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  return (
    <main className="mx-auto max-w-3xl p-8">
      <div className="mb-1 flex items-center justify-between">
        <div className="flex items-baseline gap-3">
          <h1 className="text-2xl font-semibold">My pay</h1>
          <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <Link href={`/me?year=${prev.year}&month=${prev.month}`} className="text-zinc-500 hover:text-zinc-800">← {MONTHS[prev.month - 1].slice(0, 3)}</Link>
          <span className="font-medium">{MONTHS[month - 1]} {year}</span>
          <Link href={`/me?year=${next.year}&month=${next.month}`} className="text-zinc-500 hover:text-zinc-800">{MONTHS[next.month - 1].slice(0, 3)} →</Link>
        </div>
      </div>

      {!me ? (
        <p className="mt-8 text-center text-zinc-400">No activity for this month.</p>
      ) : (
        <>
          <div className="mt-6 grid grid-cols-2 gap-4">
            <Stat label="Counted services" value={String(me.monthCountedServices)} />
            <Stat label="Tier" value={me.tierApplied ? '50 / 50' : '45 / 55'} />
            <Stat label="1–15 → you (Zelle)" value={usd(me.firstHalf.zelleToProvider)} />
            <Stat label="1–15 cash → salon" value={usd(me.firstHalf.cashToSalon)} />
            <Stat label="16–end → you (Zelle)" value={usd(me.secondHalf.zelleToProvider)} />
            <Stat label="16–end cash → salon" value={usd(me.secondHalf.cashToSalon)} />
            <Stat label="Month → you" value={usd(me.monthZelleToProvider)} highlight />
            <Stat label="Month cash → salon" value={usd(me.monthCashToSalon)} />
          </div>
          {me.secondHalf.tierBonus > 0 && (
            <p className="mt-3 text-sm text-amber-600">
              Includes a {usd(me.secondHalf.tierBonus)} tier bonus at month close (50/50 true-up).
            </p>
          )}

          <h2 className="mt-8 mb-3 text-sm font-semibold">Breakdown</h2>
          <p className="mb-4 text-xs text-zinc-500">
            Every service, with discounts and cash notes, so you can check your numbers. Copy the
            #salary block per half.
          </p>
          <ProviderTrace detail={detail} showUnmatched={false} />

          <SettlementFeedbackForm
            year={year}
            month={month}
            currentStatus={me.feedbackStatus}
            currentComment={me.feedbackComment}
          />
        </>
      )}
    </main>
  );
}

function Stat({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className={`rounded-lg p-4 ring-1 ring-zinc-200 ${highlight ? 'bg-zinc-900 text-white' : ''}`}>
      <div className={`text-xs ${highlight ? 'text-zinc-300' : 'text-zinc-500'}`}>{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums">{value}</div>
    </div>
  );
}
