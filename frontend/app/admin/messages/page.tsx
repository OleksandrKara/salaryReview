import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import MessagesView from './MessagesView';

// Shared OWNER+MANAGER conversation view — see openspec/changes/lead-followup-and-manager-inbox
// design.md D6/D7. Lives under /admin/* (not /owner/*) since both roles use it, matching this
// app's existing /admin/redos, /admin/manual-adjustments convention. The automation registry +
// toggle + flat activity log stay OWNER-only at /owner/automations.
export default async function MessagesPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/reports');

  const conversations = await serverApi.listSmsConversations();

  return (
    <main className="mx-auto max-w-5xl p-4 sm:p-8">
      <PageHeader title="Messages" role={me.role} language={me.preferredLanguage} />
      <MessagesView initialConversations={conversations} />
    </main>
  );
}
