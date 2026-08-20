import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AutomationsPanel from './AutomationsPanel';
import SmsActivityLog from './SmsActivityLog';
import TemplatesPanel from './TemplatesPanel';
import TwilioSmsSettingsForm from './TwilioSmsSettingsForm';

// Owner-only "everything SMS" page: which automations are on, the full sent/received activity
// log, and the Twilio credentials that make sending possible at all — consolidated here (was
// split across a separate /owner/automations hub) so there's one place to look, not two. The
// day-to-day per-customer conversation view (read/reply grouped by phone) lives separately at
// /admin/messages, shared with MANAGER — this page stays OWNER-only control-panel territory.
export default async function SmsSettingsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/reports');

  const [settings, automations, activity, templates] = await Promise.all([
    serverApi.getTwilioSmsSettings(),
    serverApi.listSmsAutomations(),
    serverApi.listSmsActivity(100),
    serverApi.listSmsTemplates(),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="SMS Notifications" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />

      <section>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Automations</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Automated texts we send. New automations always ship off — turn one on once you&apos;ve
          tested it.
        </p>
        <div className="mt-4">
          <AutomationsPanel initialAutomations={automations} />
        </div>
      </section>

      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Message wording</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Edit the exact text each automation sends. {'{{variables}}'} fill in automatically —
          don&apos;t remove or rename them.
        </p>
        <TemplatesPanel initialTemplates={templates} automations={automations} />
      </section>

      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Activity</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Every text we&apos;ve sent and every reply we&apos;ve received, regardless of automation.
          To reply to a customer, use{' '}
          <a href="/admin/messages" className="font-medium text-sky-700 underline">
            Messages
          </a>{' '}
          instead — this log is read-only.
        </p>
        <SmsActivityLog initialActivity={activity} />
      </section>

      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Twilio credentials</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Sends SMS to customers (e.g. confirming a 4-hand request). Leave any field blank to turn
          SMS off — nothing else breaks if credentials are unset.
        </p>
        <TwilioSmsSettingsForm initialSettings={settings} />
      </section>
    </main>
  );
}
