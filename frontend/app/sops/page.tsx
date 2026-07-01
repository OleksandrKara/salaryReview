import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import SopList from './SopList';

// Standard Operating Procedures — the shared read + acknowledge view. The API returns only the SOPs
// this role may see (audience-filtered, published, active), so the page renders what it's given.
export default async function SopsPage() {
  const [me, sops] = await Promise.all([serverApi.getMe(), serverApi.listSops()]);
  const lang = me.preferredLanguage;

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title={t(lang, 'sopTitle')} role={me.role} language={lang} />
      {me.role === 'OWNER' ? (
        <p className="mt-1 text-sm text-zinc-500">
          {t(lang, 'sopOwnerDescPre')}
          <Link href="/sops/admin" className="underline">{t(lang, 'sopOwnerDescLink')}</Link>
          {t(lang, 'sopOwnerDescPost')}
        </p>
      ) : (
        <p className="mt-1 text-sm text-zinc-500">{t(lang, 'sopStaffDesc')}</p>
      )}
      <SopList role={me.role} language={me.preferredLanguage} initialSops={sops} />
    </main>
  );
}
