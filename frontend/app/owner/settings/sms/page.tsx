import { redirect } from 'next/navigation';

// Moved to /owner/settings/automations — the page now covers both SMS (Twilio) and Email
// (Mailchimp), so a "sms" URL was misleading. This route stays only to catch old bookmarks/links.
export default function SmsSettingsRedirectPage() {
  redirect('/owner/settings/automations');
}
