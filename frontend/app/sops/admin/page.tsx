import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import SopAdmin from './SopAdmin';

// Owner SOP management. Non-owners are bounced to the staff view (the API also enforces this).
export default async function SopAdminPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/sops');
  const sops = await serverApi.listSops();
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href="/reports" className="text-xs text-zinc-400 hover:text-zinc-600">{t(lang, 'backReports')}</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">{t(lang, 'logout')}</a>
      </div>
      <h1 className="text-lg font-semibold">{t(lang, 'sopAdminTitle')}</h1>
      <p className="mt-1 text-sm text-zinc-500">{t(lang, 'sopAdminDesc')}</p>
      <SopAdmin initialSops={sops} />
    </main>
  );
}
