import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import ExperimentStatusBadge from './ExperimentStatusBadge';
import VariantTable from './VariantTable';

export default async function MarketingDashboardPage() {
  const [me, data] = await Promise.all([serverApi.getMe(), serverApi.getMarketingDashboard()]);

  if (me?.role !== 'OWNER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing" role={me.role} language={me.preferredLanguage} />

      {!data.available ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Marketing tracking isn&apos;t available yet — no experiment data has been recorded for
          this landing page.
        </div>
      ) : (
        <>
          <div className="mt-4 flex items-center gap-2">
            <span className="text-sm text-zinc-500">Landing page: {data.landingPageSlug}</span>
            <ExperimentStatusBadge status={data.experimentStatus} />
          </div>

          <div className="mt-6">
            <h2 className="mb-2 text-sm font-medium text-zinc-500">Variant performance</h2>
            <VariantTable variants={data.variants} />
          </div>
        </>
      )}
    </main>
  );
}
