import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import RetentionControls from './RetentionControls';
import NewReturningChart from './NewReturningChart';
import InfoTip from './InfoTip';
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
  // Salon-wide series (provider = null) for the fresh-vs-returning chart. When no provider is filtered
  // it's identical to `series`, so we skip the extra call and reuse it below.
  let salonSeries;
  try {
    [series, report, salonSeries] = await Promise.all([
      serverApi.getRetentionSeries(fromYear, fromMonth, toYear, toMonth, provider || undefined),
      serverApi.getRetention(toYear, toMonth),
      provider
        ? serverApi.getRetentionSeries(fromYear, fromMonth, toYear, toMonth)
        : Promise.resolve(null),
    ]);
    salonSeries = salonSeries ?? series;
  } catch {
    series = { fromYear, fromMonth, toYear, toMonth, providerRef: null, providers: [], points: [] as RetentionSeriesPoint[] };
    report = { year: toYear, month: toMonth, retentionWindowDays: 60, providers: [] };
    salonSeries = series;
  }

  const providerName = provider
    ? series.providers.find((p) => p.ref === provider)?.name ?? provider
    : 'All providers';

  // Best performer first: most clients, then most returning clients, then fewest fresh (leans least on
  // new-client supply), then highest same-day rebook rate. Lexicographic — each tie-break only decides
  // when the prior metric is equal.
  const ranked = [...report.providers].sort(
    (a, b) =>
      b.clientsSeen - a.clientsSeen ||
      b.returningToProvider - a.returningToProvider ||
      a.newToSalonViaP - b.newToSalonViaP ||
      (b.sameDayRebookRate ?? 0) - (a.sameDayRebookRate ?? 0),
  );

  // Column totals. Counts sum directly; rates can't be summed, so rebook is weighted by clients seen and
  // retention by (matured) cohort size — i.e. a true salon-wide rate, not an average of averages.
  const t = ranked.reduce(
    (a, p) => {
      a.clients += p.clientsSeen;
      a.newP += p.newToProvider;
      a.retP += p.returningToProvider;
      a.fresh += p.newToSalonViaP;
      if (p.sameDayRebookRate != null) {
        a.rebookNum += p.sameDayRebookRate * p.clientsSeen;
        a.rebookDen += p.clientsSeen;
      }
      if (p.cohortMatured && p.cohortSize > 0) {
        if (p.providerRetention != null) a.provRetNum += p.providerRetention * p.cohortSize;
        if (p.salonRetention != null) a.salonRetNum += p.salonRetention * p.cohortSize;
        a.cohort += p.cohortSize;
      }
      return a;
    },
    { clients: 0, newP: 0, retP: 0, fresh: 0, rebookNum: 0, rebookDen: 0, provRetNum: 0, salonRetNum: 0, cohort: 0 },
  );
  const totRebook = t.rebookDen ? t.rebookNum / t.rebookDen : null;
  const totProvRet = t.cohort ? t.provRetNum / t.cohort : null;
  const totSalonRet = t.cohort ? t.salonRetNum / t.cohort : null;

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
        <div className="space-y-4">
          <NewReturningChart points={series.points} label={providerName} />
          {/* Salon-wide acquisition vs retention, month by month — always the whole salon, regardless of
              the provider filter above. Fresh = clients on their first-ever salon visit. */}
          <div>
            <NewReturningChart
              points={salonSeries.points}
              label="Salon"
              newLabel="Fresh"
              testId="salon-fresh-returning-chart"
            />
            <p className="mt-1.5 px-1 text-[11px] text-zinc-400">
              Whole-salon acquisition vs retention — <span className="font-medium">Fresh</span> = clients on
              their first-ever salon visit. Always the full salon{provider ? ', not just the filtered provider' : ' (matches the chart above when no provider is filtered)'}.
            </p>
          </div>
        </div>
      )}

      {/* Latest-month per-provider scorecard — set apart from the period chart above. */}
      <div className="my-8 border-t border-zinc-200" />
      <div className="mb-3 flex items-baseline gap-2">
        <h2 className="text-sm font-semibold text-zinc-700">Provider scorecard</h2>
        <span className="text-xs text-zinc-400">{MONTHS[toMonth - 1]} {toYear} · best performer first</span>
      </div>
      {report.providers.length === 0 ? (
        <p className="rounded-lg px-4 py-4 text-sm text-zinc-400 ring-1 ring-zinc-200">No visits this month yet.</p>
      ) : (
        <>
          {/* Mobile: cards */}
          <ul data-testid="retention-scorecard-cards" className="space-y-3 sm:hidden">
            {ranked.map((p) => (
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
            {/* Mobile totals card. */}
            <li className="rounded-xl bg-zinc-50 p-4 ring-1 ring-zinc-300">
              <span className="font-semibold text-zinc-800">Total · {ranked.length} providers</span>
              <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-zinc-600">
                <Stat label="Clients seen" value={`${t.clients}`} />
                <Stat label="New / returning" value={`${t.newP} / ${t.retP}`} />
                <Stat label="Fresh (new to salon)" value={`${t.fresh}`} />
                <Stat label="Same-day rebook" value={pct(totRebook)} />
                <Stat label="Returned to provider" value={pct(totProvRet)} />
                <Stat label="Returned to salon" value={pct(totSalonRet)} />
              </div>
            </li>
          </ul>

          {/* Desktop: table. Numeric columns are right-aligned so values (and the totals row) line up. */}
          <div className="hidden overflow-x-auto rounded-lg ring-1 ring-zinc-200 sm:block">
            <table data-testid="retention-scorecard-table" className="w-full text-sm">
              <thead className="bg-zinc-50 text-[11px] uppercase tracking-wide text-zinc-500">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">Provider</th>
                  <th className="px-3 py-2 text-right font-medium">
                    Clients<InfoTip label="Clients" text="Distinct clients this provider served this month." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    New / Ret.
                    <InfoTip label="New / Returning" text="New to this provider this month / clients they had served before." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Fresh
                    <InfoTip label="Fresh" text="Brand-new salon clients this provider personally acquired — their first-ever visit was with this provider." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Rebook
                    <InfoTip label="Rebook" text="Share of this provider's visits where the client booked their next appointment the same day." />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    Retention
                    <InfoTip
                      label="Retention"
                      text={`Of this provider's new clients, the share who returned within ${report.retentionWindowDays} days — to this provider / to the salon. Recent cohorts show "too soon".`}
                    />
                  </th>
                  <th className="px-3 py-2 text-right font-medium">
                    <span className="sr-only">Risk</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((p) => (
                  <tr key={p.providerRef} className="border-t border-zinc-100">
                    <td className="px-3 py-2 font-medium text-zinc-800">{p.providerName}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.clientsSeen}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      <span className="text-green-700">{p.newToProvider}</span> / {p.returningToProvider}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{p.newToSalonViaP}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{pct(p.sameDayRebookRate)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {p.cohortMatured ? (
                        <span>
                          <span className="font-medium text-zinc-800">{pct(p.providerRetention)}</span>
                          <span className="text-zinc-400"> / {pct(p.salonRetention)}</span>
                        </span>
                      ) : (
                        <span className="text-xs italic text-zinc-400">too soon</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">{p.leakRisk ? <RiskBadge /> : null}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-zinc-300 bg-zinc-50 font-medium text-zinc-800">
                  <td className="px-3 py-2">
                    Total <span className="font-normal text-zinc-400">· {ranked.length}</span>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{t.clients}</td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <span className="text-green-700">{t.newP}</span> / {t.retP}
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{t.fresh}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{pct(totRebook)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <span>{pct(totProvRet)}</span>
                    <span className="font-normal text-zinc-400"> / {pct(totSalonRet)}</span>
                  </td>
                  <td className="px-3 py-2"></td>
                </tr>
              </tfoot>
            </table>
          </div>

          <p className="mt-3 text-[11px] text-zinc-400">
            <span className="font-medium">Fresh</span> = brand-new salon clients this provider acquired.{' '}
            <span className="font-medium text-amber-700">⚠ At risk</span> = many fresh clients but a low return
            rate. Retention shows <span className="text-zinc-600">provider</span> /{' '}
            <span className="text-zinc-400">salon</span>; recent cohorts read <span className="italic">too soon</span>{' '}
            until the {report.retentionWindowDays}-day window elapses. Total-row rates are salon-wide (weighted),
            not an average of the rows.
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
