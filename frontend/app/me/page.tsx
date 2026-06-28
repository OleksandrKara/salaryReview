import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import MonthNav from '../components/MonthNav';
import type { Feedback, HalfSettlement } from '../lib/types';
import NoShowBreakdown from './NoShowBreakdown';
import ServiceBreakdown from './ServiceBreakdown';
import SettlementFeedbackForm from './SettlementFeedbackForm';
import SalaryPopupButton from '../components/SalaryPopupButton';
import { SyncBadge } from '../components/SyncBadge';
import { InfoTip } from '../components/InfoTip';

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
  const explicit = sp.year != null || sp.month != null; // did the provider pick a month, or just land here?
  const curYear = Number(sp.year) || now.getUTCFullYear();
  const curMonth = Number(sp.month) || now.getUTCMonth() + 1;

  let year = curYear;
  let month = curMonth;
  let detail = await serverApi.getMyDetail(year, month);
  // First days of a new month: if they didn't pick a month and the current one has no activity yet,
  // fall back to the previous month (and say so) so they don't land on a blank page.
  let fellBack = false;
  if (!explicit && !detail.payout) {
    const back = shift(curYear, curMonth, -1);
    const prevDetail = await serverApi.getMyDetail(back.year, back.month);
    if (prevDetail.payout) {
      year = back.year;
      month = back.month;
      detail = prevDetail;
      fellBack = true;
    }
  }
  const me = detail.payout;
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  // Main services = counted toward the tier (gross >= cutoff); total includes sub-cutoff add-ons.
  // A cash note can cover several services, so these sum per-line units, not the raw line count.
  const totalFirst = detail.services.filter((s) => s.half === 'FIRST').reduce((n, s) => n + s.units, 0);
  const totalSecond = detail.services.filter((s) => s.half === 'SECOND').reduce((n, s) => n + s.units, 0);
  const cutoffTip = `A "main service" is one with a gross of ${usd(detail.priceCutoff)} or higher (counts toward the 50/50 tier). Add-ons below that aren't counted.`;
  // Discount the salon covered, split into cash vs the rest (card-side), per half — for the period cards.
  const isCash = (ch: string) => ch === 'CASH' || ch === 'CASH-NOTE';
  const disc = (half: 'FIRST' | 'SECOND', cash: boolean) =>
    detail.services
      .filter((s) => s.half === half && isCash(s.channel) === cash)
      .reduce((sum, s) => sum + s.discount, 0);

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <div className="flex items-baseline gap-3">
          <h1 className="text-xl font-semibold sm:text-2xl">My pay</h1>
          <Link href="/kb" className="text-xs text-zinc-400 hover:text-zinc-600">Knowledge base</Link>
          <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
        </div>
        <MonthNav base="/me" year={year} month={month} prev={prev} next={next} />
      </div>
      <div className="mb-4"><SyncBadge syncedAt={detail.syncedAt} timezone={detail.timezone} /></div>

      {fellBack && (
        <p className="mb-4 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-700 ring-1 ring-amber-200">
          No activity yet for {MONTHS[curMonth - 1]} {curYear} — showing {MONTHS[month - 1]} {year}. Use{' '}
          {MONTHS[curMonth - 1].slice(0, 3)} → above once your new month has sales.
        </p>
      )}

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

          {me.tierApplied && me.secondHalf.tierBonus > 0 && (
            <MonthBonusNote
              bonus={me.secondHalf.tierBonus}
              rebate={me.secondHalf.cashTierRebate}
              monthCard={me.firstHalf.cardRevenue + me.secondHalf.cardRevenue}
            />
          )}

          {/* Per-period cards — each with its own approve / request-correction */}
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <PeriodCard title="1–15" halfKey="FIRST" half={me.firstHalf} total={totalFirst} cutoffTip={cutoffTip}
              cardDisc={disc('FIRST', false)} cashDisc={disc('FIRST', true)}
              message={detail.firstHalfMessage} year={year} month={month} feedback={me.firstFeedback}
              needsNoteCount={me.firstHalfSuspiciousNoNotes} />
            <PeriodCard title="16–end" halfKey="SECOND" half={me.secondHalf} total={totalSecond} cutoffTip={cutoffTip}
              cardDisc={disc('SECOND', false)} cashDisc={disc('SECOND', true)}
              message={detail.secondHalfMessage} year={year} month={month} feedback={me.secondFeedback}
              needsNoteCount={me.secondHalfSuspiciousNoNotes} />
          </div>

          <ServiceBreakdown detail={detail} />
        </>
      )}

      <NoShowBreakdown rows={detail.noShows} />
    </main>
  );
}

function Headline({ label, value, big, tip }: { label: string; value: string; big?: boolean; tip?: string }) {
  return (
    <div>
      <div className="text-xs text-zinc-300">{label}{tip && <InfoTip text={tip} />}</div>
      <div className={`mt-1 font-semibold tabular-nums ${big ? 'text-2xl' : 'text-xl'}`}>{value}</div>
    </div>
  );
}

// The 50/50 tier bonus is a WHOLE-MONTH amount (the uplift on both periods' card), paid at month close —
// so it lands inside the 16–end total. This month-level note makes that explicit, since providers were
// reading the bonus as a 16–end-only thing.
function MonthBonusNote({ bonus, rebate, monthCard }: { bonus: number; rebate: number; monthCard: number }) {
  const total = bonus + rebate;
  const detail =
    `You hit the 50/50 tier this month, so you earn the higher rate on your whole month — both periods ` +
    `(1–15 and 16–end), not just one: ${usd(bonus)} extra on your card (the uplift on ${usd(monthCard)})` +
    (rebate > 0 ? ` and a ${usd(rebate)} rebate on the cash you hand back to the salon` : '') +
    `, ${usd(total)} in total. It's settled at month close, so it lands inside your 16–end total below.`;
  return (
    <div className="mt-4 flex items-start gap-2 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-800 ring-1 ring-amber-200">
      <span aria-hidden>🎉</span>
      <div>
        <div className="font-medium">50/50 tier reached — month bonus {usd(total)}<InfoTip text={detail} /></div>
        <p className="mt-0.5 text-xs text-amber-700">
          {rebate > 0 && <>{usd(bonus)} on card + {usd(rebate)} cash rebate · </>}covers the whole month, paid inside your 16–end total below.
        </p>
      </div>
    </div>
  );
}

function NeedsNoteBadge({ count, year, month, half }: {
  count: number; year: number; month: number; half: 'FIRST' | 'SECOND';
}) {
  if (count <= 0) return null;
  const display = count > 99 ? '99+' : String(count);
  return (
    <Link
      href={`/me/suspicious?year=${year}&month=${month}&half=${half}`}
      title={`${count} appointment${count === 1 ? '' : 's'} need${count === 1 ? 's' : ''} a note added in Square`}
      data-testid={`me-needs-note-badge-${half}`}
      // Same amber palette as the owner-side suspicious badge — visual consistency: "attention
      // needed, click to resolve" reads the same regardless of who's looking.
      className="inline-flex items-center gap-1 rounded bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-amber-300 hover:bg-amber-100"
    >
      <span aria-hidden>⚠</span>
      {display} need{count === 1 ? 's' : ''} note
    </Link>
  );
}

function PeriodCard({
  title,
  halfKey,
  half,
  total,
  cutoffTip,
  cardDisc,
  cashDisc,
  message,
  year,
  month,
  feedback,
  needsNoteCount,
}: {
  title: string;
  halfKey: 'FIRST' | 'SECOND';
  half: HalfSettlement;
  total: number;
  cutoffTip: string;
  cardDisc: number;
  cashDisc: number;
  message: string | null;
  year: number;
  month: number;
  feedback: Feedback | null;
  needsNoteCount: number;
}) {
  const discTip = 'The salon absorbs discounts — your pay is on the full menu price, so this discount didn’t reduce what you earned here.';
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-sm font-semibold">{title}</h2>
          <NeedsNoteBadge count={needsNoteCount} year={year} month={month} half={halfKey} />
        </div>
        {message && <SalaryPopupButton title={`#salary · ${title}`} message={message} />}
      </div>
      <dl className="space-y-1.5 text-sm">
        <Row label="Main services" value={String(half.countedServices)} tip={cutoffTip} />
        <Row label="Total services" value={String(total)} />
        <Row label="Card" value={usd(half.cardRevenue)} />
        {cardDisc > 0 && <Row label="discount covered" value={usd(cardDisc)} tip={discTip} sub />}
        <Row label="Cash" value={usd(half.cashCollected)} />
        {cashDisc > 0 && <Row label="discount covered" value={usd(cashDisc)} tip={discTip} sub />}
        <Row label="Tips (after fee)" value={usd(half.tipsAfterFee)} />
      </dl>
      <div className="mt-3 space-y-1.5 border-t border-zinc-200 pt-3 text-sm">
        <Row label="→ You (Zelle)" value={usd(half.zelleToProvider)} strong />
        {half.tierBonus > 0 && (
          <Row label="incl. month 50/50 bonus" value={usd(half.tierBonus)} hint
            tip="This is your whole-month 50/50 bonus (it covers both 1–15 and 16–end, not just this period). It's paid here at month close. See the note above the periods, or #salary, for the math." />
        )}
        <Row label="Cash → salon" value={usd(half.cashToSalon)} />
        {half.cashTierRebate > 0 && (
          <Row label="incl. tier cash rebate" value={usd(half.cashTierRebate)} hint
            tip="Part of your whole-month 50/50 bonus: it lowers the cash you hand back to the salon. See the note above the periods." />
        )}
      </div>
      <SettlementFeedbackForm year={year} month={month} half={halfKey} current={feedback}
        approveBlockedReason={needsNoteCount > 0
          ? `${needsNoteCount} appointment${needsNoteCount === 1 ? '' : 's'} need${needsNoteCount === 1 ? 's' : ''} a note before you can approve. Review the list above, or contact management.`
          : null} />
    </div>
  );
}

function Row({ label, value, strong, hint, tip, sub }:
  { label: string; value: string; strong?: boolean; hint?: boolean; tip?: string; sub?: boolean }) {
  // `sub` = an indented sub-line under the row above (e.g. "discount covered" under Card/Cash), emerald.
  const tone = sub ? 'text-emerald-700' : hint ? 'text-amber-600' : 'text-zinc-500';
  return (
    <div className={`flex items-baseline justify-between ${hint || sub ? 'text-xs' : ''} ${sub ? 'pl-3' : ''}`}>
      <dt className={tone}>{sub && <span aria-hidden className="mr-1 text-emerald-400">↳</span>}{label}{tip && <InfoTip text={tip} />}</dt>
      <dd className={`tabular-nums ${strong ? 'font-semibold text-zinc-900' : sub ? 'text-emerald-700' : hint ? 'text-amber-600' : ''}`}>{value}</dd>
    </div>
  );
}
