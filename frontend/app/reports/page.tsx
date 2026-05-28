import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import type { ProviderPayout } from '../lib/types';
import GrantTierButton from './GrantTierButton';
import SalaryButtons from './SalaryButtons';

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

function TierBadge({ p }: { p: ProviderPayout }) {
  if (p.autoQualified)
    return <span className="rounded bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 ring-1 ring-green-300">50/50 · earned</span>;
  if (p.tierManuallyGranted)
    return <span className="rounded bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-amber-300">50/50 · granted</span>;
  return <span className="rounded bg-zinc-100 px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-zinc-300">45/55</span>;
}

// A period/month cell: Zelle paid to the provider (top) over cash returned to the salon (below).
function MoneyCell({ zelle, cash, bonus = 0, strong = false }: { zelle: number; cash: number; bonus?: number; strong?: boolean }) {
  return (
    <div className="text-right tabular-nums">
      <div className={strong ? 'font-semibold' : 'text-zinc-700'}>
        {usd(zelle)}
        {bonus > 0 && <span className="ml-1 text-xs font-normal text-amber-600">+{usd(bonus)}</span>}
      </div>
      <div className="text-xs text-zinc-400">cash {usd(cash)}</div>
    </div>
  );
}

function FeedbackBadge({ p }: { p: ProviderPayout }) {
  if (p.feedbackStatus === 'APPROVED')
    return <span title="Provider approved" className="rounded bg-green-50 px-1.5 py-0.5 text-[10px] font-medium text-green-700 ring-1 ring-green-300">✓ approved</span>;
  if (p.feedbackStatus === 'CHANGES_REQUESTED')
    return <span title={p.feedbackComment ?? 'Changes requested'} className="rounded bg-red-50 px-1.5 py-0.5 text-[10px] font-medium text-red-700 ring-1 ring-red-300">⚠ changes</span>;
  return null;
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
    <main className="mx-auto max-w-6xl p-8">
      <div className="mb-1 flex items-center justify-between">
        <div className="flex items-baseline gap-3">
          <h1 className="text-2xl font-semibold">Salary report</h1>
          {me?.role === 'OWNER' && (
            <Link href="/admin/users" className="text-xs text-zinc-400 hover:text-zinc-600">Users</Link>
          )}
          <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
        </div>
        <div className="flex items-center gap-3 text-sm">
          <Link href={`/reports?year=${prev.year}&month=${prev.month}`} className="text-zinc-500 hover:text-zinc-800">← {MONTHS[prev.month - 1].slice(0, 3)}</Link>
          <span className="font-medium">{MONTHS[month - 1]} {year}</span>
          <Link href={`/reports?year=${next.year}&month=${next.month}`} className="text-zinc-500 hover:text-zinc-800">{MONTHS[next.month - 1].slice(0, 3)} →</Link>
        </div>
      </div>
      <p className="mb-6 text-xs text-zinc-500">
        Tier at {cfg.tierServiceThreshold}+ services ≥ {usd(report.priceCutoff)} · {Math.round(cfg.tierRate * 100)}/{Math.round((1 - cfg.tierRate) * 100)} vs {Math.round(cfg.baseRate * 100)}/{Math.round((1 - cfg.baseRate) * 100)} · tips −{(cfg.cardTipFeeRate * 100).toFixed(1)}% · {report.timezone}
        <br />Each period shows <span className="font-medium text-zinc-600">Zelle paid to provider</span> over <span className="text-zinc-400">cash returned to salon</span>.
      </p>

      <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
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
              <tr key={p.providerId} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">
                  <span className="flex items-center gap-2">
                    {p.name}
                    <FeedbackBadge p={p} />
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
                <td className="px-3 py-2"><MoneyCell zelle={p.firstHalf.zelleToProvider} cash={p.firstHalf.cashToSalon} bonus={p.firstHalf.tierBonus} /></td>
                <td className="px-3 py-2"><MoneyCell zelle={p.secondHalf.zelleToProvider} cash={p.secondHalf.cashToSalon} bonus={p.secondHalf.tierBonus} /></td>
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
        {report.diagnostics.unmatchedLineItems > 0 && ` · ${report.diagnostics.unmatchedLineItems} unmatched (${usd(report.diagnostics.unmatchedRevenue)})`}
      </p>
    </main>
  );
}
