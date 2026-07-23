import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import FunnelView from './FunnelView';
import { parsePeriodParams, periodToBounds } from '../period';

const DEFAULT_SLUG = 'mani';

export default async function MarketingFunnelPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string; period?: string; from?: string; to?: string }>;
}) {
  const params = await searchParams;
  const { slug } = params;
  const resolvedSlug = slug ?? DEFAULT_SLUG;
  // Same shared period filter every marketing tab reads (see PeriodFilter/../period) — defaults
  // to 'all' here specifically (not the usual 'mtd'), since a funnel's drop-off shape is normally
  // read over its whole history rather than just the current month.
  const bounds = periodToBounds(parsePeriodParams(params, 'all'));
  const [me, data, pages] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingFunnel(slug, undefined, bounds.from, bounds.to),
    serverApi.getMarketingPages(),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        How far visitors get through the booking flow before dropping off — each landing page
        reports its own steps, so different flows (e.g. contact info first vs. last) are compared
        by relative position rather than assumed to be identical.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector actually remounts this with
            fresh initialData instead of the already-mounted instance's stale state. */}
        <FunnelView key={resolvedSlug} initialData={data} slug={resolvedSlug} pages={pages} role={me.role} />
      </div>
    </main>
  );
}
