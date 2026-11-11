import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';

// The manager dashboard is gone: managers work from the CRM (live shift summary on top) and reach
// everything else through the menu. This route stays only so old bookmarks and links land there.
export default async function ManagerPage() {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'OWNER') redirect('/reports');
  redirect('/admin/leads');
}
