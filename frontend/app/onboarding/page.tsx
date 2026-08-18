import { serverApi } from '../lib/serverApi';
import type { SquareRosterEntry } from '../lib/types';
import PageHeader from '../components/PageHeader';
import SquareConnectionForm from '../owner/settings/square/SquareConnectionForm';
import UsersManager from '../admin/users/UsersManager';

// Phase 6.4 (multi-tenant-salon-platform): a guided first-setup flow for a business the platform
// admin just created — combines the two forms an owner previously had to discover on their own
// (Square Connection, Users) into one page, in the order they actually need to happen. Both
// forms are the real, already-shipped settings pages embedded as-is (no new backend endpoints) —
// this page is purely a UX consolidation, not a wizard with its own state machine: an owner who's
// already fully set up can revisit this page anytime (nothing here is one-time-use or hidden
// after completion), and both sections work independently, so there's nothing to "get stuck on"
// if Square isn't connected yet when they add their first manager.
export default async function OnboardingPage() {
  const [connection, users, providers, roster] = await Promise.all([
    serverApi.getSquareConnection(),
    serverApi.listUsers(),
    serverApi.listProviders(),
    // Roster import needs Square already connected — tolerates a brand-new business where it
    // isn't yet, same as /admin/users's own page.
    serverApi.getSquareRoster().catch(() => [] as SquareRosterEntry[]),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Getting Started" />
      <p className="mt-1 text-sm text-zinc-500">
        Two things to set up before this business is ready to use: connect Square, then invite
        your managers and providers.
      </p>

      <section className="mt-8">
        <h2 className="text-sm font-semibold text-zinc-900">Step 1 — Connect Square</h2>
        <p className="mt-1 text-sm text-zinc-500">
          The access token is encrypted at rest and never shown again after saving — paste it
          here, not in chat or Slack.
        </p>
        <SquareConnectionForm initialConnection={connection} />
      </section>

      <section className="mt-10">
        <h2 className="text-sm font-semibold text-zinc-900">Step 2 — Invite your team</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Add each manager and provider as an account. Once Square is connected above, you can
          also import candidates straight from your Square team roster instead of typing them in
          by hand.
        </p>
        <UsersManager initialUsers={users} providers={providers} roster={roster} />
      </section>
    </main>
  );
}
