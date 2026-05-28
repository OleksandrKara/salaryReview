import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import type { HalfSettlement } from '../lib/types';
import DiscountBreakdown from './DiscountBreakdown';
import ServiceBreakdown from './ServiceBreakdown';
import SettlementFeedbackForm from './SettlementFeedbackForm';
import SalaryPopupButton from '../components/SalaryPopupButton';

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

// Provider's read-only view of their own month: a month headline, a card per period (1–15 / 16–end)
// with services / card / cash / tips / payout and a #salary popup, then the discount + service
// breakdowns and approve / request-correction.
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

  const firstCount = detail.services.filter((s) => s.half === 'FIRST').length;
  const secondCount = detail.services.filter((s) => s.half === 'SECOND').length;

  return (
    <main className="mx-auto max-w-3xl p-8">
      <div className="mb-6 flex items-center justify-between">
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
          {/* Month headline */}
          <div className="grid grid-cols-2 gap-4 rounded-lg bg-zinc-900 p-5 text-white sm:grid-cols-4">
            <Headline label="Month → you" value={usd(me.monthZelleToProvider)} big />
            <Headline label="Cash → salon" value={usd(me.monthCashToSalon)} />
            <Headline label="Services" value={String(firstCount + secondCount)} />
            <Headline label="Tier" value={me.tierApplied ? '50 / 50' : '45 / 55'} />
          </div>

          {/* Per-period cards */}
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <PeriodCard title="1–15" half={me.firstHalf} count={firstCount} message={detail.firstHalfMessage} />
            <PeriodCard title="16–end" half={me.secondHalf} count={secondCount} message={detail.secondHalfMessage} />
          </div>

          <DiscountBreakdown services={detail.services} />

          <ServiceBreakdown detail={detail} />

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

function Headline({ label, value, big }: { label: string; value: string; big?: boolean }) {
  return (
    <div>
      <div className="text-xs text-zinc-300">{label}</div>
      <div className={`mt-1 font-semibold tabular-nums ${big ? 'text-2xl' : 'text-xl'}`}>{value}</div>
    </div>
  );
}

function PeriodCard({
  title,
  half,
  count,
  message,
}: {
  title: string;
  half: HalfSettlement;
  count: number;
  message: string | null;
}) {
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">{title}</h2>
        {message && <SalaryPopupButton title={`#salary · ${title}`} message={message} />}
      </div>
      <dl className="space-y-1.5 text-sm">
        <Row label="Services" value={String(count)} />
        <Row label="Card" value={usd(half.cardRevenue)} />
        <Row label="Cash" value={usd(half.cashCollected)} />
        <Row label="Tips (after fee)" value={usd(half.tipsAfterFee)} />
      </dl>
      <div className="mt-3 space-y-1.5 border-t border-zinc-200 pt-3 text-sm">
        <Row label="→ You (Zelle)" value={usd(half.zelleToProvider)} strong />
        {half.tierBonus > 0 && <Row label="incl. tier bonus" value={usd(half.tierBonus)} hint />}
        <Row label="Cash → salon" value={usd(half.cashToSalon)} />
      </div>
    </div>
  );
}

function Row({ label, value, strong, hint }: { label: string; value: string; strong?: boolean; hint?: boolean }) {
  const tone = hint ? 'text-amber-600' : 'text-zinc-500';
  return (
    <div className={`flex items-baseline justify-between ${hint ? 'text-xs' : ''}`}>
      <dt className={tone}>{label}</dt>
      <dd className={`tabular-nums ${strong ? 'font-semibold text-zinc-900' : hint ? 'text-amber-600' : ''}`}>{value}</dd>
    </div>
  );
}
