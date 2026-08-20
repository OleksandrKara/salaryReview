import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import ReviewsView from './ReviewsView';

// Checkout-review-request replies, grouped by provider — every rating (1-5) and every reply with
// no digit at all, plus an overall and per-provider average. See backend V120 /
// CheckoutReviewInsightsService. Owner-only, same as the /api/owner/reviews route it reads from.
export default async function ReviewsPage() {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'MANAGER') redirect('/manager');
  if (me.role === 'ADS_MANAGER') redirect('/owner/marketing');

  const overview = await serverApi.getReviews();

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <PageHeader title="Reviews" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <p className="mb-6 text-xs text-zinc-500">
        Every reply to the post-checkout &quot;how&apos;d we do?&quot; text, per provider — including replies with
        no number in them at all.
      </p>
      <ReviewsView overview={overview} />
    </main>
  );
}
