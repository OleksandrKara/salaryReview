import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import type { ProviderPayout } from '../lib/types';
import GrantTierButton from './GrantTierButton';

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

  const totalToProviders = report.providers.reduce((s, p) => s + p.monthZelleToProvider, 0);
  const totalCashToSalon = report.providers.reduce((s, p) => s + p.monthCashToSalon, 0);

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
      </p>

      <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Provider</th>
              <th className="px-3 py-2 text-right">Services</th>
              <th className="px-3 py-2">Tier</th>
              <th className="px-3 py-2 text-right">1–15 (Zelle)</th>
              <th className="px-3 py-2 text-right">16–end (Zelle)</th>
              <th className="px-3 py-2 text-right">Month → provider</th>
              <th className="px-3 py-2 text-right">Cash → salon</th>
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
                <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usd(p.firstHalf.zelleToProvider)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-600">
                  {usd(p.secondHalf.zelleToProvider)}
                  {p.secondHalf.tierBonus > 0 && (
                    <span className="ml-1 text-xs text-amber-600">+{usd(p.secondHalf.tierBonus)} bonus</span>
                  )}
                </td>
                <td className="px-3 py-2 text-right font-semibold tabular-nums">{usd(p.monthZelleToProvider)}</td>
                <td className="px-3 py-2 text-right tabular-nums text-zinc-600">{usd(p.monthCashToSalon)}</td>
              </tr>
            ))}
            {report.providers.length === 0 && (
              <tr><td colSpan={7} className="px-3 py-6 text-center text-zinc-400">No activity for this month.</td></tr>
            )}
          </tbody>
          {report.providers.length > 0 && (
            <tfoot className="border-t border-zinc-200 bg-zinc-50 font-medium">
              <tr>
                <td className="px-3 py-2" colSpan={5}>Totals</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd(totalToProviders)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{usd(totalCashToSalon)}</td>
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
