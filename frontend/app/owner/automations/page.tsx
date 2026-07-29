import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import PageHeader from '../../components/PageHeader';
import AutomationsView from './AutomationsView';

// OWNER-only hub: see which SMS automations exist, whether each is enabled and for whom, plus the
// full sent/received activity log — see openspec/changes/sms-automations-hub. MANAGER access is
// explicitly deferred (design.md D9).
export default async function AutomationsPage() {
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER') redirect('/reports');

  const [automations, activity] = await Promise.all([
    serverApi.listSmsAutomations(),
    serverApi.listSmsActivity(100),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="SMS Automations" role={me.role} language={me.preferredLanguage} />
      <AutomationsView initialAutomations={automations} initialActivity={activity} />
    </main>
  );
}
