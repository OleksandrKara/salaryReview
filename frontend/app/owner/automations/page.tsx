import { redirect } from 'next/navigation';

// Moved into /owner/settings/automations — every automation (SMS + email), its activity, and its
// credentials now live on one page. This route stays only to catch old bookmarks/links.
export default function AutomationsRedirectPage() {
  redirect('/owner/settings/automations');
}
