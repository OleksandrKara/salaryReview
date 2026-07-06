import Link from 'next/link';
import { Suspense } from 'react';
import { serverApi } from '../lib/serverApi';
import type { Feedback, ProviderPayout } from '../lib/types';
import PageHeader from '../components/PageHeader';
import MonthNav from '../components/MonthNav';
import GrantTierButton from './GrantTierButton';
import NoShowFeesPanel from './NoShowFeesPanel';
import ClearFeedbackButton from './ClearFeedbackButton';
import RevenuePulse from './RevenuePulse';
import SalaryButtons from './SalaryButtons';
import { SyncBadge } from '../components/SyncBadge';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });

function shift(year: number, month: number, by: number) {
  const idx = (month - 1) + by;
  return { year: year + Math.floor(idx / 12), month: ((idx % 12) + 12) % 12 + 1 };
}

function TierBadge({ p }: { p: ProviderPayout }) {
  if (p.autoQualified)
    return <span className="rounded bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 ring-1 ring-green-300">50/50 · earned</span>;
  if (p.tierManuallyGranted)
    return <span className="rounded bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-amber-300">50/50 · granted</span>;
  return <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300">45/55</span>;
}

// Badge linking to the suspicious-bookings review page for one provider × half. Hidden when count = 0.
function SuspiciousBadge({ count, providerId, year, month, half }: {
  count: number; providerId: number; year: number; month: number; half: 'FIRST' | 'SECOND';
}) {
  if (count <= 0) return null;
  const display = count > 99 ? '99+' : String(count);
  return (
    <Link
      href={`/reports/${providerId}/suspicious?year=${year}&month=${month}&half=${half}`}
      title={`${count} appointment${count === 1 ? '' : 's'} need${count === 1 ? 's' : ''} review`}
      data-testid={`suspicious-badge-${providerId}-${half}`}
      className="ml-1 inline-flex items-center gap-0.5 rounded bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-amber-300 hover:bg-amber-100"
    >
      ⚠ {display}
    </Link>
  );
}

// Badge linking to the cancelled-appointments review page for one provider × half. Owner-only,
// informational (never blocks salary). Red to read as a "please verify on camera" warning, distinct
// from the amber suspicious badge. Hidden when count = 0.
function CancelledBadge({ count, providerId, year, month, half }: {
  count: number; providerId: number; year: number; month: number; half: 'FIRST' | 'SECOND';
}) {
  if (count <= 0) return null;
  const display = count > 99 ? '99+' : String(count);
  return (
    <Link
      href={`/reports/${providerId}/cancellations?year=${year}&month=${month}&half=${half}`}
      title={`${count} cancelled appointment${count === 1 ? '' : 's'} to verify on camera`}
      data-testid={`cancelled-badge-${providerId}-${half}`}
      className="ml-1 inline-flex items-center gap-0.5 rounded bg-rose-50 px-1.5 py-0.5 text-[10px] font-medium text-rose-700 ring-1 ring-rose-300 hover:bg-rose-100"
    >
      ⊘ {display}
    </Link>
  );
}

// A period/month cell: Zelle paid to the provider (top) over cash returned to the salon (below).
function MoneyCell({ zelle, cash, bonus = 0, strong = false, badge }: {
  zelle: number; cash: number; bonus?: number; strong?: boolean; badge?: React.ReactNode;
}) {
  return (
    <div className="text-right tabular-nums">
      <div className={strong ? 'font-semibold' : 'text-zinc-700'}>
        {usd(zelle)}
        {bonus > 0 && (
          <span className="ml-1 cursor-help text-xs font-normal text-amber-600"
            title="Whole-month 50/50 tier bonus (both periods), paid at month close — open the provider's #salary for the math.">
            +{usd(bonus)}
          </span>
        )}
        {badge}
      </div>
      <div className="text-xs text-zinc-400">cash {usd(cash)}</div>
    </div>
  );
}

// One period line on the mobile card: label … Zelle → provider · cash → salon.
function MobileMoney({ label, zelle, cash, bonus = 0, strong = false, badge }: {
  label: string; zelle: number; cash: number; bonus?: number; strong?: boolean; badge?: React.ReactNode;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-zinc-500">{label}{badge}</dt>
      <dd className="text-right tabular-nums">
        <span className={strong ? 'font-semibold' : ''}>{usd(zelle)}</span>
        {bonus > 0 && (
          <span className="text-xs text-amber-600"
            title="Whole-month 50/50 tier bonus (both periods), paid at month close."> +{usd(bonus)}</span>
        )}
        <span className="text-zinc-400"> · cash {usd(cash)}</span>
      </dd>
    </div>
  );
}

// A provider responds per period now — show a small badge per half (1–15 / 16–end), with an owner/
// manager "×" to clear (undo) that response.
function HalfBadge({ providerId, year, month, half, label, fb }: {
  providerId: number; year: number; month: number; half: 'FIRST' | 'SECOND'; label: string; fb: Feedback | null;
}) {
  if (!fb) return null;
  const approved = fb.status === 'APPROVED';
  const cls = approved
    ? 'bg-green-50 text-green-700 ring-green-300'
    : 'bg-red-50 text-red-700 ring-red-300';
  const title = approved ? `${label}: approved` : (fb.comment ? `${label}: ${fb.comment}` : `${label}: changes requested`);
  return (
    <span className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium ring-1 ${cls}`}>
      <span title={title}>{label} {approved ? '✓' : '⚠'}</span>
      <ClearFeedbackButton providerId={providerId} year={year} month={month} half={half} />
    </span>
  );
}

function FeedbackBadge({ p, year, month }: { p: ProviderPayout; year: number; month: number }) {
  return (
    <>
      <HalfBadge providerId={p.providerId} year={year} month={month} half="FIRST" label="1–15" fb={p.firstFeedback} />
      <HalfBadge providerId={p.providerId} year={year} month={month} half="SECOND" label="16–end" fb={p.secondFeedback} />
    </>
  );
}

// Next 16: searchParams is a Promise — must be awaited.
export default async function ReportsPage({
  searchParams,
}: {
  searchParams: Promise<{ year?: string; month?: string }>;
}) {
  const sp = await searchParams;
  const now = new Date();
  const year = Number(sp.year) || now.getUTCFullYear();
  const month = Number(sp.month) || now.getUTCMonth() + 1;

  const [report, me] = await Promise.all([
    serverApi.getSettlementPreview(year, month),
    serverApi.getMe(),
  ]);
  const prev = shift(year, month, -1);
  const next = shift(year, month, 1);
  const cfg = report.config;

  const sum = (f: (p: ProviderPayout) => number) => report.providers.reduce((s, p) => s + f(p), 0);
  const totals = {
    z1: sum((p) => p.firstHalf.zelleToProvider),
    c1: sum((p) => p.firstHalf.cashToSalon),
    z2: sum((p) => p.secondHalf.zelleToProvider),
    c2: sum((p) => p.secondHalf.cashToSalon),
    zM: sum((p) => p.monthZelleToProvider),
    cM: sum((p) => p.monthCashToSalon),
  };

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Salary report" role={me.role} language={me.preferredLanguage} />
      <div className="-mt-4 mb-1">
        <MonthNav base="/reports" year={year} month={month} prev={prev} next={next} />
      </div>
      <div className="mb-3"><SyncBadge syncedAt={report.syncedAt} timezone={report.timezone} /></div>
      <p className="mb-6 text-xs text-zinc-500">
        Tier at {cfg.tierServiceThreshold}+ services ≥ {usd(report.priceCutoff)} · {Math.round(cfg.tierRate * 100)}/{Math.round((1 - cfg.tierRate) * 100)} vs {Math.round(cfg.baseRate * 100)}/{Math.round((1 - cfg.baseRate) * 100)} · tips −{(cfg.cardTipFeeRate * 100).toFixed(1)}% · {report.timezone}
        <br />Each period shows <span className="font-medium text-zinc-600">Zelle paid to provider</span> over <span className="text-zinc-400">cash returned to salon</span>.
      </p>

      {/* Revenue pulse — owner-only, streams in via Suspense so it doesn't delay the table. */}
      {me?.role === 'OWNER' && (
        <Suspense fallback={
          <div className="mb-4 h-28 animate-pulse rounded-xl bg-zinc-100" />
        }>
          <RevenuePulse year={year} month={month} />
        </Suspense>
      )}

      {/* Mobile: a card per provider (the table is too wide for a phone). */}
      <div data-testid="reports-provider-list" className="flex flex-col gap-3 sm:hidden">
        {report.providers.map((p) => (
          <div key={p.providerId} data-testid={`provider-card-${p.providerId}`} className="rounded-lg p-4 ring-1 ring-zinc-200">
            <div className="flex items-start justify-between gap-2">
              <span className="flex flex-wrap items-center gap-2 font-medium">{p.name}<FeedbackBadge p={p} year={year} month={month} /></span>
              <Link href={`/reports/${p.providerId}?year=${year}&month=${month}`}
                className="shrink-0 text-xs text-zinc-400 hover:text-zinc-700">Details →</Link>
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-zinc-500">
              <TierBadge p={p} />
              {!p.autoQualified && (
                <GrantTierButton providerId={p.providerId} year={year} month={month} granted={p.tierManuallyGranted} />
              )}
              <span>{p.monthCountedServices}/{cfg.tierServiceThreshold} services</span>
            </div>
            <dl className="mt-3 space-y-1 text-sm">
              <MobileMoney label="Month → you" zelle={p.monthZelleToProvider} cash={p.monthCashToSalon} strong />
              <MobileMoney label="1–15" zelle={p.firstHalf.zelleToProvider} cash={p.firstHalf.cashToSalon} bonus={p.firstHalf.tierBonus}
                badge={<><SuspiciousBadge count={p.firstHalfSuspicious} providerId={p.providerId} year={year} month={month} half="FIRST" /><CancelledBadge count={p.firstHalfCancellations} providerId={p.providerId} year={year} month={month} half="FIRST" /></>} />
              <MobileMoney label="16–end" zelle={p.secondHalf.zelleToProvider} cash={p.secondHalf.cashToSalon} bonus={p.secondHalf.tierBonus}
                badge={<><SuspiciousBadge count={p.secondHalfSuspicious} providerId={p.providerId} year={year} month={month} half="SECOND" /><CancelledBadge count={p.secondHalfCancellations} providerId={p.providerId} year={year} month={month} half="SECOND" /></>} />
            </dl>
            <div className="mt-3"><SalaryButtons name={p.name} firstHalfMessage={p.firstHalfMessage} secondHalfMessage={p.secondHalfMessage} /></div>
          </div>
        ))}
        {report.providers.length === 0 && (
          <p className="rounded-lg p-4 text-center text-zinc-400 ring-1 ring-zinc-200">No activity for this month.</p>
        )}
      </div>

      {/* Desktop: the full table. */}
      <div data-testid="reports-provider-table" className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Provider</th>
              <th className="px-3 py-2 text-right">Services</th>
              <th className="px-3 py-2">Tier</th>
              <th className="px-3 py-2 text-right">1–15</th>
              <th className="px-3 py-2 text-right">16–end</th>
              <th className="px-3 py-2 text-right">Month</th>
              <th className="px-3 py-2">#salary</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {report.providers.map((p) => (
              <tr key={p.providerId} data-testid={`provider-row-${p.providerId}`} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">
                  <span className="flex items-center gap-2">
                    {p.name}
                    <FeedbackBadge p={p} year={year} month={month} />
                    <Link href={`/reports/${p.providerId}?year=${year}&month=${month}`}
                      className="text-xs font-normal text-zinc-400 hover:text-zinc-700">Details →</Link>
                  </span>
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {p.monthCountedServices}
                  <span className="text-zinc-400"> / {cfg.tierServiceThreshold}</span>
                </td>
                <td className="px-3 py-2">
                  <div className="flex items-center gap-2">
                    <TierBadge p={p} />
                    {!p.autoQualified && (
                      <GrantTierButton providerId={p.providerId} year={year} month={month} granted={p.tierManuallyGranted} />
                    )}
                  </div>
                </td>
                <td className="px-3 py-2"><MoneyCell zelle={p.firstHalf.zelleToProvider} cash={p.firstHalf.cashToSalon} bonus={p.firstHalf.tierBonus}
                  badge={<><SuspiciousBadge count={p.firstHalfSuspicious} providerId={p.providerId} year={year} month={month} half="FIRST" /><CancelledBadge count={p.firstHalfCancellations} providerId={p.providerId} year={year} month={month} half="FIRST" /></>} /></td>
                <td className="px-3 py-2"><MoneyCell zelle={p.secondHalf.zelleToProvider} cash={p.secondHalf.cashToSalon} bonus={p.secondHalf.tierBonus}
                  badge={<><SuspiciousBadge count={p.secondHalfSuspicious} providerId={p.providerId} year={year} month={month} half="SECOND" /><CancelledBadge count={p.secondHalfCancellations} providerId={p.providerId} year={year} month={month} half="SECOND" /></>} /></td>
                <td className="px-3 py-2"><MoneyCell zelle={p.monthZelleToProvider} cash={p.monthCashToSalon} strong /></td>
                <td className="px-3 py-2">
                  <SalaryButtons name={p.name} firstHalfMessage={p.firstHalfMessage} secondHalfMessage={p.secondHalfMessage} />
                </td>
              </tr>
            ))}
            {report.providers.length === 0 && (
              <tr><td colSpan={7} className="px-3 py-6 text-center text-zinc-400">No activity for this month.</td></tr>
            )}
          </tbody>
          {report.providers.length > 0 && (
            <tfoot className="border-t border-zinc-200 bg-zinc-50 font-medium">
              <tr>
                <td className="px-3 py-2" colSpan={3}>Totals</td>
                <td className="px-3 py-2"><MoneyCell zelle={totals.z1} cash={totals.c1} /></td>
                <td className="px-3 py-2"><MoneyCell zelle={totals.z2} cash={totals.c2} /></td>
                <td className="px-3 py-2"><MoneyCell zelle={totals.zM} cash={totals.cM} strong /></td>
                <td className="px-3 py-2"></td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      <p className="mt-3 text-xs text-zinc-400">
        Synced from Square · {report.diagnostics.orders} orders, {report.diagnostics.matchedLineItems} matched
        {report.diagnostics.prepaidMatches > 0 && `, ${report.diagnostics.prepaidMatches} prepaid`}
        , {report.diagnostics.cashNotes} cash notes
        {report.diagnostics.cashNotesSkipped > 0 && ` (${report.diagnostics.cashNotesSkipped} skipped — already checked out as cash)`}
        {report.diagnostics.ownerComps > 0 && `, ${report.diagnostics.ownerComps} owner comp${report.diagnostics.ownerComps > 1 ? 's' : ''}`}
        {report.diagnostics.unmatchedLineItems > 0 && ` · ${report.diagnostics.unmatchedLineItems} unmatched (${usd(report.diagnostics.unmatchedRevenue)})`}
      </p>

      <NoShowFeesPanel year={year} month={month} />
    </main>
  );
}
