import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import LtvView from './LtvView';

export default async function MarketingLtvPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string }>;
}) {
  const params = await searchParams;
  // slug is passed through as-is (possibly undefined) — the backend resolves its own
  // business-scoped default landing page when omitted. Previously this hardcoded a literal
  // "mani" fallback, which meant any business other than AK.LUX.NAILS always got an explicit
  // request for AK.LUX.NAILS' own page — same fix as ads-report/page.tsx.
  const { slug } = params;
  const [me, data] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingLtv(slug),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto w-full min-w-0 max-w-6xl p-4 sm:p-8">
      <PageHeader title="Customer Lifetime Value" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MarketingTabs />

      <p className="mt-4 text-sm text-zinc-500">
        All-time revenue from real, paying customers only — grouped by how they first found you.
        Unlike Ads Report above, this never resets with the period filter: it always covers a
        customer&apos;s full history, from their first visit to today. Pairs with the ad spend
        figures there to see which channel&apos;s customers are actually worth it long-term, not
        just which one books the cheapest first visit.
      </p>

      <div className="mt-6">
        {/* Keyed by slug so switching pages via the shared selector — a client-side navigation
            that wouldn't otherwise remount this component — actually re-fetches for the new page
            instead of keeping whatever it last showed. */}
        <LtvView key={slug} initialData={data} slug={slug} />
      </div>
    </main>
  );
}
