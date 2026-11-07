import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import MyTime from './MyTime';

export default async function MyTimePage() {
  const me = await serverApi.getMe();
  if (me.role !== 'PROVIDER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-2xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'tcMyTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <MyTime language={lang ?? 'EN'} />
    </main>
  );
}
