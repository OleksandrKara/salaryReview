import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import MessagesTabs from '../MessagesTabs';
import EmailSendsView from './EmailSendsView';

// Sibling route to /admin/messages (see MessagesTabs) — a flat, unbounded log of every email this
// business has ever sent, separate from the phone-keyed SMS conversation list since a pure-email
// campaign's recipients (owner request 2026-09-05: color_booster_winback_oneoff) mostly have no
// SMS thread at all to attach an inline card to.
export default async function EmailSendsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/reports');

  const sends = await serverApi.listEmailSends();

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col p-4 sm:p-8">
      <div className="shrink-0">
        <PageHeader title="Messages" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
        <MessagesTabs active="emails" />
      </div>
      <div className="min-h-0 flex-1 rounded-lg ring-1 ring-zinc-200 sm:ring-0">
        <EmailSendsView sends={sends} />
      </div>
    </main>
  );
}
