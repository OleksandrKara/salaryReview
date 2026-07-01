import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import SopAdmin from './SopAdmin';

// Owner SOP management. Non-owners are bounced to the staff view (the API also enforces this).
export default async function SopAdminPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/sops');
  const sops = await serverApi.listSops();
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <PageHeader title={t(lang, 'sopAdminTitle')} role={me.role} language={lang} />
      <p className="mt-1 text-sm text-zinc-500">{t(lang, 'sopAdminDesc')}</p>
      <SopAdmin initialSops={sops} />
    </main>
  );
}
