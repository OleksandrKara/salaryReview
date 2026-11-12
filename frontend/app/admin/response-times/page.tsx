import { redirect } from 'next/navigation';
import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import ResponseTimesView from './ResponseTimesView';

export default async function ResponseTimesPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER' && me.role !== 'MANAGER') redirect('/me');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto w-full min-w-0 max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'rtTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <p className="mb-5 text-xs text-zinc-500">{t(lang, 'rtDesc')}</p>
      <ResponseTimesView language={lang} activeBusinessId={me.activeBusinessId} />
    </main>
  );
}
