import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import MonthNav from '../components/MonthNav';
import type { Feedback, HalfSettlement, Language } from '../lib/types';
import { t, tf, monthName, monthShort } from '../lib/i18n';
import NoShowBreakdown from './NoShowBreakdown';
import ServiceBreakdown from './ServiceBreakdown';
import SettlementFeedbackForm from './SettlementFeedbackForm';
import SalaryPopupButton from '../components/SalaryPopupButton';
import PageHeader from '../components/PageHeader';
import { SyncBadge } from '../components/SyncBadge';
import { InfoTip } from '../components/InfoTip';

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
  const principal = await serverApi.getMe();
  const lang = principal.preferredLanguage;
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);

  // Main services = counted toward the tier (gross >= cutoff); total includes sub-cutoff add-ons.
  // A cash note can cover several services, so these sum per-line units, not the raw line count.
  const totalFirst = detail.services.filter((s) => s.half === 'FIRST').reduce((n, s) => n + s.units, 0);
  const totalSecond = detail.services.filter((s) => s.half === 'SECOND').reduce((n, s) => n + s.units, 0);
  const cutoffTip = tf(lang, 'meCutoffTip', { amount: usd(detail.priceCutoff) });
  // Discount the salon covered, split into cash vs the rest (card-side), per half — for the period cards.
  const isCash = (ch: string) => ch === 'CASH' || ch === 'CASH-NOTE';
  const disc = (half: 'FIRST' | 'SECOND', cash: boolean) =>
    detail.services
      .filter((s) => s.half === half && isCash(s.channel) === cash)
      .reduce((sum, s) => sum + s.discount, 0);

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader
        title={t(lang, 'navMyPay')}
        role={principal.role}
        language={lang}
        activeBusinessId={principal.activeBusinessId}
        businesses={principal.businesses}
      />
      <div className="-mt-4 mb-4">
        <MonthNav base="/me" year={year} month={month} prev={prev} next={next} language={lang} />
      </div>
      <div className="mb-4"><SyncBadge syncedAt={detail.syncedAt} timezone={detail.timezone} language={lang} /></div>

      {fellBack && (
        <p className="mb-4 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-700 ring-1 ring-amber-200">
          {tf(lang, 'meFellback', {
            cur: `${monthName(lang, curMonth - 1)} ${curYear}`,
            shown: `${monthName(lang, month - 1)} ${year}`,
            mon: monthShort(lang, curMonth - 1),
          })}
        </p>
      )}

      {!me ? (
        <p className="mt-8 text-center text-zinc-400">{t(lang, 'meNoActivityMonth')}</p>
      ) : (
        <>
          {/* Month headline */}
          <div className="grid grid-cols-2 gap-4 rounded-lg bg-zinc-900 p-5 text-white sm:grid-cols-4">
            <Headline label={t(lang, 'meMonthToYou')} value={usd(me.monthZelleToProvider)} big />
            <Headline label={t(lang, 'meCashToSalon')} value={usd(me.monthCashToSalon)} />
            <Headline label={t(lang, 'meMainServices')} value={String(me.monthCountedServices)} tip={cutoffTip} />
            <Headline label={t(lang, 'meTier')} value={me.tierApplied ? '50 / 50' : '45 / 55'} />
          </div>

          {me.tierApplied && me.secondHalf.tierBonus > 0 && (
            <MonthBonusNote
              bonus={me.secondHalf.tierBonus}
              rebate={me.secondHalf.cashTierRebate}
              monthCard={me.firstHalf.cardRevenue + me.secondHalf.cardRevenue}
              lang={lang}
            />
          )}

          {/* Per-period cards — each with its own approve / request-correction */}
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <PeriodCard title="1–15" halfKey="FIRST" half={me.firstHalf} total={totalFirst} cutoffTip={cutoffTip}
              cardDisc={disc('FIRST', false)} cashDisc={disc('FIRST', true)}
              message={detail.firstHalfMessage} year={year} month={month} feedback={me.firstFeedback}
              needsNoteCount={me.firstHalfSuspiciousNoNotes} lang={lang} />
            <PeriodCard title={t(lang, 'mePeriodEnd')} halfKey="SECOND" half={me.secondHalf} total={totalSecond} cutoffTip={cutoffTip}
              cardDisc={disc('SECOND', false)} cashDisc={disc('SECOND', true)}
              message={detail.secondHalfMessage} year={year} month={month} feedback={me.secondFeedback}
              needsNoteCount={me.secondHalfSuspiciousNoNotes} lang={lang} />
          </div>

          <ServiceBreakdown detail={detail} language={lang} />
        </>
      )}

      <NoShowBreakdown rows={detail.noShows} language={lang} />
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
function MonthBonusNote({ bonus, rebate, monthCard, lang }:
  { bonus: number; rebate: number; monthCard: number; lang: Language | null }) {
  const total = bonus + rebate;
  const rebateClause = rebate > 0 ? tf(lang, 'meBonusNoteRebate', { rebate: usd(rebate) }) : '';
  const detail = tf(lang, 'meBonusNoteTip', {
    bonus: usd(bonus), monthCard: usd(monthCard), rebate: rebateClause, total: usd(total),
  });
  return (
    <div className="mt-4 flex items-start gap-2 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-800 ring-1 ring-amber-200">
      <span aria-hidden>🎉</span>
      <div>
        <div className="font-medium">{tf(lang, 'meBonusReached', { total: usd(total) })}<InfoTip text={detail} /></div>
        <p className="mt-0.5 text-xs text-amber-700">
          {rebate > 0 && <>{tf(lang, 'meBonusSubRebate', { bonus: usd(bonus), rebate: usd(rebate) })}</>}{t(lang, 'meBonusSubTail')}
        </p>
      </div>
    </div>
  );
}

function NeedsNoteBadge({ count, year, month, half, lang }: {
  count: number; year: number; month: number; half: 'FIRST' | 'SECOND'; lang: Language | null;
}) {
  if (count <= 0) return null;
  const display = count > 99 ? '99+' : String(count);
  return (
    <Link
      href={`/me/suspicious?year=${year}&month=${month}&half=${half}`}
      title={tf(lang, 'meNeedsNoteTip', { n: count })}
      data-testid={`me-needs-note-badge-${half}`}
      // Same amber palette as the owner-side suspicious badge — visual consistency: "attention
      // needed, click to resolve" reads the same regardless of who's looking.
      className="inline-flex items-center gap-1 rounded bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-amber-300 hover:bg-amber-100"
    >
      <span aria-hidden>⚠</span>
      {tf(lang, 'meNeedsNote', { n: display })}
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
  lang,
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
  lang: Language | null;
}) {
  const discTip = t(lang, 'meDiscTip');
  return (
    <div className="rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-sm font-semibold">{title}</h2>
          <NeedsNoteBadge count={needsNoteCount} year={year} month={month} half={halfKey} lang={lang} />
        </div>
        {message && <SalaryPopupButton title={`#salary · ${title}`} message={message} />}
      </div>
      <dl className="space-y-1.5 text-sm">
        <Row label={t(lang, 'meMainServices')} value={String(half.countedServices)} tip={cutoffTip} />
        <Row label={t(lang, 'meTotalServices')} value={String(total)} />
        <Row label={t(lang, 'meCard')} value={usd(half.cardRevenue)} />
        {cardDisc > 0 && <Row label={t(lang, 'meDiscountCovered')} value={usd(cardDisc)} tip={discTip} sub />}
        <Row label={t(lang, 'meCash')} value={usd(half.cashCollected)} />
        {cashDisc > 0 && <Row label={t(lang, 'meDiscountCovered')} value={usd(cashDisc)} tip={discTip} sub />}
        <Row label={t(lang, 'meTips')} value={usd(half.tipsAfterFee)} />
      </dl>
      <div className="mt-3 space-y-1.5 border-t border-zinc-200 pt-3 text-sm">
        <Row label={t(lang, 'meToYouZelle')} value={usd(half.zelleToProvider)} strong />
        {half.tierBonus > 0 && (
          <Row label={t(lang, 'meInclBonus')} value={usd(half.tierBonus)} hint tip={t(lang, 'meBonusSubTail')} />
        )}
        <Row label={t(lang, 'meCashToSalon')} value={usd(half.cashToSalon)} />
        {half.cashTierRebate > 0 && (
          <Row label={t(lang, 'meInclRebate')} value={usd(half.cashTierRebate)} hint tip={t(lang, 'meBonusSubTail')} />
        )}
      </div>
      <SettlementFeedbackForm year={year} month={month} half={halfKey} current={feedback} language={lang}
        approveBlockedReason={needsNoteCount > 0 ? tf(lang, 'meApproveBlocked', { n: needsNoteCount }) : null} />
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
