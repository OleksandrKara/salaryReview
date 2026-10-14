import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import SeoDashboardView from './SeoDashboardView';
import SiteSwitch, { resolveSite } from '../SiteSwitch';

// design.md D6: the tab is hidden entirely (not just disabled) when seo-monitoring.enabled is off
// for this business — a direct visit still redirects rather than rendering a 404 page, same
// precedent as ads-report's role-based redirect below.
export default async function SeoMonitoringPage({ searchParams }: { searchParams: Promise<{ site?: string }> }) {
  const params = await searchParams;
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'ADS_MANAGER') redirect('/reports');
  if (!me.features.seoMonitoringEnabled) redirect('/owner/marketing');

  const pages = await serverApi.getMarketingPages().catch(() => []);
  const hasAcademy = pages.some((p) => p.slug === 'academy');
  const site = resolveSite(hasAcademy, params.site);
  const overview = await serverApi.getSeoOverview(undefined, site === 'all' ? undefined : site);
  // Only possible if the feature flag flipped off between the checks above and this fetch — the
  // backend independently enforces the same gate (design.md D6), so this isn't reachable in the
  // normal flow, just a safe fallback.
  if (overview === null) redirect('/owner/marketing');

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="SEO" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        Organic search performance and Core Web Vitals, synced daily (Search Console) and weekly
        (PageSpeed). Recommendations below are sourced from Google&rsquo;s own published thresholds.
      </p>

      {site && <SiteSwitch basePath="/owner/marketing/seo" site={site} />}
      {site === 'academy' && (
        <p className="mt-2 text-xs text-zinc-500">
          Search numbers for pmu-annakara.com/academy/ and the old edu.pmu-annakara.com. Visitors (GA4),
          Core Web Vitals and technical issues are for the whole domain.
        </p>
      )}

      <div className="mt-6">
        <SeoDashboardView
          key={site ?? 'all'}
          initialData={overview}
          site={site === 'all' ? undefined : site}
          canUseAiAdvisor={me.features.aiSeoAdvisorEnabled && me.role === 'OWNER'}
        />
      </div>
    </main>
  );
}
