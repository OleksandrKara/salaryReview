import { redirect } from 'next/navigation';

// Moved into /owner/settings/automations as a tab — Telegram sits alongside SMS/Email as a third
// notification channel now, not a separate settings page. This route stays only to catch old
// bookmarks/links; ?tab=telegram deep-links straight to the right tab.
export default function TelegramSettingsRedirectPage() {
  redirect('/owner/settings/automations?tab=telegram');
}
