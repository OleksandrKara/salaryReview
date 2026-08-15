import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import SquareConnectionForm from './SquareConnectionForm';

// Owner-only. Connects this business's Square account (access token + location id) — replaces the
// earlier practice of sharing the token in chat for a human to hand-enter into the database. Every
// save is validated against a real Square call before anything is stored.
export default async function SquareConnectionPage() {
  const connection = await serverApi.getSquareConnection();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="Square connection" />
      <p className="mt-1 text-sm text-zinc-500">
        Connects this business&rsquo;s Square account. The access token is encrypted at rest and never
        shown again after saving — paste it here, not in chat or Slack.
      </p>
      <SquareConnectionForm initialConnection={connection} />
    </main>
  );
}
