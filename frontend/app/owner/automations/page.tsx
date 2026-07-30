import { redirect } from 'next/navigation';

// Moved into /owner/settings/sms — everything SMS-related (automations, activity, credentials)
// now lives on one page. This route stays only to catch old bookmarks/links.
export default function AutomationsRedirectPage() {
  redirect('/owner/settings/sms');
}
