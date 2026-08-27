import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import AutomationsTabs from './AutomationsTabs';

// Owner-only automations hub: which automations are on, across both channels (SMS via Twilio,
// email via Mailchimp — see the win-back automations' SMS-first/email-fallback design), plus the
// activity log and credentials for each. Was "/owner/settings/sms" — renamed once the page grew a
// real email channel alongside SMS, so the URL/nav no longer says "sms" for a page that's half
// about email. Split into tabs (see AutomationsTabs) so switching between "just SMS" and "just
// email" doesn't mean scrolling past the other channel's forms. The day-to-day per-customer SMS
// conversation view (read/reply grouped by phone) lives separately at /admin/messages, shared with
// MANAGER — this page stays OWNER-only control-panel territory.
export default async function AutomationsSettingsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/reports');

  const [settings, mailchimpSettings, mailchimpActivity, automations, templates, promoTerms, serviceLifecycleRoles] = await Promise.all([
    serverApi.getTwilioSmsSettings(),
    serverApi.getMailchimpSettings(),
    serverApi.getMailchimpActivity(),
    serverApi.listSmsAutomations(),
    serverApi.listSmsTemplates(),
    serverApi.listPromoTerms(),
    serverApi.listServiceLifecycleRoles(),
  ]);

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title="Automations" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />

      <AutomationsTabs
        automations={automations}
        serviceLifecycleRoles={serviceLifecycleRoles}
        promoTerms={promoTerms}
        templates={templates}
        twilioSettings={settings}
        mailchimpSettings={mailchimpSettings}
        mailchimpActivity={mailchimpActivity}
      />
    </main>
  );
}
