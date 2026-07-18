import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import TelegramSettingsForm from './TelegramSettingsForm';

// Owner-only. Configures the bot token/chat id used to alert staff on Telegram when a 4-hand
// request comes in from mani or akluxnails-home. Both apps call salaryReview's internal relay
// endpoint to send the alert — they never see the bot token themselves (see PR description).
export default async function TelegramSettingsPage() {
  const settings = await serverApi.getTelegramSettings();

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="Telegram notifications" />
      <p className="mt-1 text-sm text-zinc-500">
        Alerts staff on Telegram when a 4-hand request comes in from either booking site. Leave
        either field blank to turn alerts off — nothing else breaks if they&rsquo;re unset.
      </p>
      <TelegramSettingsForm initialSettings={settings} />
    </main>
  );
}
