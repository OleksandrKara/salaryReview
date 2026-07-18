import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import TwilioSmsSettingsForm from './TwilioSmsSettingsForm';

// Owner-only. Configures the Twilio credentials used to send SMS (e.g. the 4-hand request
// confirmation). mani and akluxnails-home call salaryReview's internal relay endpoint to send —
// they never see the Account SID/API key/secret themselves (same pattern as Telegram settings).
export default async function TwilioSmsSettingsPage() {
  const settings = await serverApi.getTwilioSmsSettings();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="SMS notifications (Twilio)" />
      <p className="mt-1 text-sm text-zinc-500">
        Sends SMS to customers (e.g. confirming a 4-hand request). Leave any field blank to turn
        SMS off — nothing else breaks if credentials are unset.
      </p>
      <TwilioSmsSettingsForm initialSettings={settings} />
    </main>
  );
}
