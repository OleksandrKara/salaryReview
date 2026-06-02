import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import type { Feedback, HalfSettlement } from '../lib/types';
import DiscountBreakdown from './DiscountBreakdown';
import ServiceBreakdown from './ServiceBreakdown';
import SettlementFeedbackForm from './SettlementFeedbackForm';
import SalaryPopupButton from '../components/SalaryPopupButton';
import { SyncBadge } from '../components/SyncBadge';

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

  // Main services = counted toward the tier (gross >= cutoff); total includes sub-cutoff add-ons.
  // A cash note can cover several services, so these sum per-line units, not the raw line count.
  const totalFirst = detail.services.filter((s) => s.half === 'FIRST').reduce((n, s) => n + s.units, 0);
  const totalSecond = detail.services.filter((s) => s.half === 'SECOND').reduce((n, s) => n + s.units, 0);
  const cutoffTip = `A "main service" is one with a gross of ${usd(detail.priceCutoff)} or higher (counts toward the 50/50 tier). Add-ons below that aren't counted.`;

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <div className="flex items-baseline gap-3">
          <h1 className="text-xl font-semibold sm:text-2xl">My pay</h1>
          <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <Link href={`/me?year=${prev.year}&month=${prev.month}`} className="text-zinc-500 hover:text-zinc-800">← {MONTHS[prev.month - 1].slice(0, 3)}</Link>
          <span className="font-medium">{MONTHS[month - 1]} {year}</span>
          <Link href={`/me?year=${next.year}&month=${next.month}`} className="text-zinc-500 hover:text-zinc-800">{MONTHS[next.month - 1].slice(0, 3)} →</Link>
        </div>
      </div>
      <div className="mb-4"><SyncBadge syncedAt={detail.syncedAt} timezone={detail.timezone} /></div>

      {!me ? (
        <p className="mt-8 text-center text-zinc-400">No activity for this month.</p>
      ) : (
        <>
          {/* Month headline */}
          <div className="grid grid-cols-2 gap-4 rounded-lg bg-zinc-900 p-5 text-white sm:grid-cols-4">
            <Headline label="Month → you" value={usd(me.monthZelleToProvider)} big />
            <Headline label="Cash → salon" value={usd(me.monthCashToSalon)} />
            <Headline label="Main services" value={String(me.monthCountedServices)} tip={cutoffTip} />
            <Headline label="Tier" value={me.tierApplied ? '50 / 50' : '45 / 55'} />
          </div>

          {/* Per-period cards — each with its own approve / request-correction */}
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <PeriodCard title="1–15" halfKey="FIRST" half={me.firstHalf} total={totalFirst} cutoffTip={cutoffTip}
              message={detail.firstHalfMessage} year={year} month={month} feedback={me.firstFeedback} />
            <PeriodCard title="16–end" halfKey="SECOND" half={me.secondHalf} total={totalSecond} cutoffTip={cutoffTip}
              message={detail.secondHalfMessage} year={year} month={month} feedback={me.secondFeedback} />
          </div>

          <DiscountBreakdown services={detail.services} />

          <ServiceBreakdown detail={detail} />
        </>
      )}
    </main>
  );
}

function Headline({ label, value, big, tip }: { label: string; value: string; big?: boolean; tip?: string }) {
  return (
    <div>
      <div className="text-xs text-zinc-300" title={tip}>{label}{tip && <span className="ml-0.5 cursor-help text-zinc-400">ⓘ</span>}</div>
      <div className={`mt-1 font-semibold tabular-nums ${big ? 'text-2xl' : 'text-xl'}`}>{value}</div>
    </div>
  );
}

function PeriodCard({
  title,
  halfKey,
  half,
  total,
  cutoffTip,
  message,
  year,
  month,
  feedback,
}: {
  title: string;
  halfKey: 'FIRST' | 'SECOND';
  half: HalfSettlement;
  total: number;
  cutoffTip: string;
  message: string | null;
  year: number;
  month: number;
  feedback: Feedback | null;
}) {
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold">{title}</h2>
        {message && <SalaryPopupButton title={`#salary · ${title}`} message={message} />}
      </div>
      <dl className="space-y-1.5 text-sm">
        <Row label="Main services" value={String(half.countedServices)} tip={cutoffTip} />
        <Row label="Total services" value={String(total)} />
        <Row label="Card" value={usd(half.cardRevenue)} />
        <Row label="Cash" value={usd(half.cashCollected)} />
        <Row label="Tips (after fee)" value={usd(half.tipsAfterFee)} />
      </dl>
      <div className="mt-3 space-y-1.5 border-t border-zinc-200 pt-3 text-sm">
        <Row label="→ You (Zelle)" value={usd(half.zelleToProvider)} strong />
        {half.tierBonus > 0 && <Row label="incl. tier bonus" value={usd(half.tierBonus)} hint />}
        <Row label="Cash → salon" value={usd(half.cashToSalon)} />
      </div>
      <SettlementFeedbackForm year={year} month={month} half={halfKey} current={feedback} />
    </div>
  );
}

function Row({ label, value, strong, hint, tip }: { label: string; value: string; strong?: boolean; hint?: boolean; tip?: string }) {
  const tone = hint ? 'text-amber-600' : 'text-zinc-500';
  return (
    <div className={`flex items-baseline justify-between ${hint ? 'text-xs' : ''}`}>
      <dt className={tone} title={tip}>{label}{tip && <span className="ml-0.5 cursor-help text-zinc-400">ⓘ</span>}</dt>
      <dd className={`tabular-nums ${strong ? 'font-semibold text-zinc-900' : hint ? 'text-amber-600' : ''}`}>{value}</dd>
    </div>
  );
}
