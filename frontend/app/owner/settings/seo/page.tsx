import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import SeoConnectionForm from './SeoConnectionForm';

// Owner-only. Connects this business's Search Console/GA4/PageSpeed credentials — same paste-in
// model as Square/Telegram (no OAuth flow in this change, see proposal.md Non-Goals). Every
// credential is encrypted at rest and never shown again after saving.
export default async function SeoConnectionPage() {
  const connection = await serverApi.getSeoConnection();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="SEO monitoring connection" />
      <p className="mt-1 text-sm text-zinc-500">
        Connects Search Console, Google Analytics 4, and PageSpeed Insights for this business.
        Credentials are encrypted at rest — paste them here, not in chat or Slack. See the runbook
        for how to generate a service-account key if you don&rsquo;t have one yet.
      </p>
      <SeoConnectionForm initialConnection={connection} />
    </main>
  );
}
