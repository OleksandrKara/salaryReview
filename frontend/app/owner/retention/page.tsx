import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import RetentionControls from './RetentionControls';
import NewReturningChart from './NewReturningChart';
import type { RetentionSeriesPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const pct = (n: number | null) => (n == null ? '—' : `${Math.round(n * 100)}%`);

// Owner-only provider retention. A period view (like /overview): a new-vs-returning chart over a date
// range, all providers by default or one selected, plus the latest-month per-provider scorecard.
export default async function RetentionPage({
  searchParams,
}: {
  searchParams: Promise<{
    fromYear?: string; fromMonth?: string; toYear?: string; toMonth?: string; provider?: string;
  }>;
}) {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect(me.role === 'MANAGER' ? '/manager' : '/me');

  const sp = await searchParams;
  const now = new Date();
  const curYear = now.getUTCFullYear();
  const curMonth = now.getUTCMonth() + 1;
  // Default: last 12 complete months (exclude the current, unfinished month).
  const defToMonth = curMonth === 1 ? 12 : curMonth - 1;
  const defToYear = curMonth === 1 ? curYear - 1 : curYear;
  const defFrom = new Date(Date.UTC(defToYear, defToMonth - 1 - 11, 1));

  const fromYear = Number(sp.fromYear) || defFrom.getUTCFullYear();
  const fromMonth = Number(sp.fromMonth) || defFrom.getUTCMonth() + 1;
  const toYear = Number(sp.toYear) || defToYear;
  const toMonth = Number(sp.toMonth) || defToMonth;
  const provider = sp.provider ?? '';

  let series;
  let report;
  try {
    [series, report] = await Promise.all([
      serverApi.getRetentionSeries(fromYear, fromMonth, toYear, toMonth, provider || undefined),
      serverApi.getRetention(toYear, toMonth),
    ]);
  } catch {
    series = { fromYear, fromMonth, toYear, toMonth, providerRef: null, providers: [], points: [] as RetentionSeriesPoint[] };
    report = { year: toYear, month: toMonth, retentionWindowDays: 60, providers: [] };
  }

  const providerName = provider
    ? series.providers.find((p) => p.ref === provider)?.name ?? provider
    : 'All providers';

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-1 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Provider retention</h1>
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">← Reports</Link>
      </div>
      <p className="mb-4 text-xs text-zinc-500">
        New vs returning clients over time — for the whole salon or one provider. Retention (below) = of a
        provider&apos;s new clients in a month, how many returned within {report.retentionWindowDays} days.
      </p>

      <div className="mb-5">
        <RetentionControls
          fromYear={fromYear} fromMonth={fromMonth} toYear={toYear} toMonth={toMonth}
          provider={provider} providers={series.providers}
        />
      </div>

      {series.points.length === 0 ? (
        <p className="rounded-lg px-4 py-6 text-sm text-zinc-400 ring-1 ring-zinc-200">
          No visits recorded for this period yet. (The ledger backfills on deploy and refreshes daily.)
        </p>
      ) : (
        <NewReturningChart points={series.points} label={providerName} />
      )}

      {/* Latest-month per-provider scorecard. */}
      <h2 className="mt-8 mb-2 text-sm font-semibold text-zinc-700">
        {MONTHS[toMonth - 1]} {toYear} — provider scorecard
      </h2>
      {report.providers.length === 0 ? (
        <p className="rounded-lg px-4 py-4 text-sm text-zinc-400 ring-1 ring-zinc-200">No visits this month yet.</p>
      ) : (
        <>
          {/* Mobile: cards */}
          <ul className="space-y-3 sm:hidden">
            {report.providers.map((p) => (
              <li key={p.providerRef} className="rounded-xl p-4 ring-1 ring-zinc-200">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-zinc-800">{p.providerName}</span>
                  {p.leakRisk ? <RiskBadge /> : null}
                </div>
                <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-zinc-600">
                  <Stat label="Clients seen" value={`${p.clientsSeen}`} />
                  <Stat label="New / returning" value={`${p.newToProvider} / ${p.returningToProvider}`} />
                  <Stat label="Fresh (new to salon)" value={`${p.newToSalonViaP}`} />
                  <Stat label="Same-day rebook" value={pct(p.sameDayRebookRate)} />
                  <Stat label="Returned to provider" value={p.cohortMatured ? pct(p.providerRetention) : 'too soon'} />
                  <Stat label="Returned to salon" value={p.cohortMatured ? pct(p.salonRetention) : 'too soon'} />
                </div>
              </li>
            ))}
          </ul>

          {/* Desktop: table */}
          <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table className="w-full text-sm">
              <thead className="bg-zinc-50 text-left text-[11px] uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2">Provider</th>
                  <th className="px-3 py-2">Clients</th>
                  <th className="px-3 py-2" title="New to this provider / returning to them">New / Ret.</th>
                  <th className="px-3 py-2" title="New salon clients this provider acquired">Fresh</th>
                  <th className="px-3 py-2">Rebook</th>
                  <th className="px-3 py-2" title="Of new clients, % who returned (to provider / to salon)">Retention</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody>
                {report.providers.map((p) => (
                  <tr key={p.providerRef} className="border-t border-zinc-100">
                    <td className="px-3 py-2 font-medium text-zinc-800">{p.providerName}</td>
                    <td className="px-3 py-2 tabular-nums">{p.clientsSeen}</td>
                    <td className="px-3 py-2 tabular-nums">
                      <span className="text-green-700">{p.newToProvider}</span> / {p.returningToProvider}
                    </td>
                    <td className="px-3 py-2 tabular-nums">{p.newToSalonViaP}</td>
                    <td className="px-3 py-2 tabular-nums">{pct(p.sameDayRebookRate)}</td>
                    <td className="px-3 py-2 tabular-nums">
                      {p.cohortMatured ? (
                        <span>
                          <span className="font-medium text-zinc-800">{pct(p.providerRetention)}</span>
                          <span className="text-zinc-400"> / {pct(p.salonRetention)}</span>
                        </span>
                      ) : (
                        <span className="text-xs italic text-zinc-400">too soon</span>
                      )}
                    </td>
                    <td className="px-3 py-2">{p.leakRisk ? <RiskBadge /> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="mt-3 text-[11px] text-zinc-400">
            <span className="font-medium">Fresh</span> = brand-new salon clients this provider acquired.{' '}
            <span className="font-medium text-amber-700">⚠ At risk</span> = many fresh clients but a low return
            rate. Retention shows <span className="text-zinc-600">provider</span> /{' '}
            <span className="text-zinc-400">salon</span>; recent cohorts read <span className="italic">too soon</span>{' '}
            until the {report.retentionWindowDays}-day window elapses.
          </p>
        </>
      )}
    </main>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <span>
      <span className="text-zinc-400">{label}: </span>
      <span className="font-medium tabular-nums text-zinc-800">{value}</span>
    </span>
  );
}

function RiskBadge() {
  return (
    <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">⚠ At risk</span>
  );
}
