import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MarketingTabs from '../MarketingTabs';
import LtvView from './LtvView';

// Deliberately a local literal, not imported from MarketingTabs (a 'use client' module) — a server
// component importing a named non-component export from a client file gets Next's opaque "client
// reference" proxy back instead of the real string, which stringifies to a poisoned placeholder
// (confirmed live: slug arrived at the backend as the literal source of that placeholder function).
// funnel/page.tsx already does it this way for the same reason.
const DEFAULT_SLUG = 'mani';

export default async function MarketingLtvPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string }>;
}) {
  const params = await searchParams;
  const slug = params.slug ?? DEFAULT_SLUG;
  const [me, data] = await Promise.all([
    serverApi.getMe(),
    serverApi.getMarketingLtv(slug),
  ]);

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Customer Lifetime Value" role={me.role} language={me.preferredLanguage} />
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
