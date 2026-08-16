import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import SetupRequiredNotice from '../../components/SetupRequiredNotice';
import RetentionControls from './RetentionControls';
import NewReturningChart from './NewReturningChart';
import ProviderScorecard, { rankProviders, totalScorecard } from './ProviderScorecard';
import type { RetentionSeriesPoint } from '../../lib/types';

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Provider retention. A period view (like /overview): a new-vs-returning chart over a date range, all
// providers by default or one selected, plus the latest-month per-provider scorecard. Owner + manager
// (view-only — the page has no edit actions, so read access alone gives managers the same visibility).
export default async function RetentionPage({
  searchParams,
}: {
  searchParams: Promise<{
    fromYear?: string; fromMonth?: string; toYear?: string; toMonth?: string; provider?: string;
  }>;
}) {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'ADS_MANAGER') redirect('/owner/marketing');

  // Managers can't reach the Square settings page — fails open (see /admin/redos's own comment).
  const squareConnection = await serverApi.getSquareConnection().catch(() => null);
  if (squareConnection && !squareConnection.accessTokenSet) {
    return (
      <main className="mx-auto max-w-6xl p-4 sm:p-8">
        <PageHeader title="Provider retention" role={me.role} language={me.preferredLanguage} />
        <SetupRequiredNotice
          title="Connect Square to see retention"
          message="Retention is calculated from real Square booking history, which needs a Square connection first."
          ctaHref={me.role === 'OWNER' ? '/owner/settings/square' : undefined}
          ctaLabel={me.role === 'OWNER' ? 'Connect Square' : undefined}
        />
      </main>
    );
  }

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

  const ranked = rankProviders(report.providers);
  const { totals, totRebook, totProvRet, totSalonRet } = totalScorecard(ranked);

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <PageHeader title="Provider retention" role={me.role} language={me.preferredLanguage} />
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

      {/* Latest-month per-provider scorecard — set apart from the period chart above. */}
      <div className="my-8 border-t border-zinc-200" />
      <ProviderScorecard
        ranked={ranked}
        retentionWindowDays={report.retentionWindowDays}
        monthLabel={`${MONTHS[toMonth - 1]} ${toYear}`}
        totals={totals}
        totRebook={totRebook}
        totProvRet={totProvRet}
        totSalonRet={totSalonRet}
      />
    </main>
  );
}
