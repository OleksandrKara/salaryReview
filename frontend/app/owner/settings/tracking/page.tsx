import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import TrackingSettingsForm from './TrackingSettingsForm';

// Owner-only. Microsoft Clarity project id per public site this business owns — not a secret (a
// Clarity project id is visible in any live page's own rendered source), so unlike Square/SEO/SMS
// nothing here is masked or encrypted.
export default async function TrackingSettingsPage() {
  const sites = await serverApi.getTrackingConfig();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="Tracking codes" />
      <p className="mt-1 text-sm text-zinc-500">
        Paste each site&rsquo;s Microsoft Clarity project id to start recording session heatmaps and
        recordings for it. Find yours in Clarity under Settings &rarr; Setup &rarr; the code after{' '}
        <code className="text-xs">clarity.ms/tag/</code>. Leave a field blank to stop tracking that
        site.
      </p>
      <TrackingSettingsForm initialSites={sites} />
    </main>
  );
}
