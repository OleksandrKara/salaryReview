import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import AiVisibilityView from './AiVisibilityView';
import SiteSwitch, { resolveSite } from '../SiteSwitch';

// openspec/changes/ai-visibility-monitoring: hidden (redirect) when ai-visibility.enabled is off,
// same rule as the SEO tab.
export default async function AiVisibilityPage({ searchParams }: { searchParams: Promise<{ site?: string }> }) {
  const params = await searchParams;
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'ADS_MANAGER') redirect('/reports');
  if (!me.features.aiVisibilityEnabled) redirect('/owner/marketing');

  const pages = await serverApi.getMarketingPages().catch(() => []);
  const site = resolveSite(pages.some((p) => p.slug === 'academy'), params.site);
  const apiSite = site === 'all' ? undefined : site;
  const overview = await serverApi.getAiVisibilityOverview(apiSite);
  if (overview === null) redirect('/owner/marketing');

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="AI" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />
      <p className="mt-4 text-sm text-zinc-500">
        Does ChatGPT, Google AI, Gemini, Perplexity or Claude recommend us when someone asks for the best place?
        Every tracked question is asked to each assistant once a week.
      </p>
      {site && <SiteSwitch basePath="/owner/marketing/ai" site={site} />}
      <div className="mt-6">
        <AiVisibilityView key={site ?? 'all'} initialData={overview} canEdit={me.role === 'OWNER'} site={apiSite} />
      </div>
    </main>
  );
}
