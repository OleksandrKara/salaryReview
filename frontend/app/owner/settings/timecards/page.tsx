import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import { t } from '../../../lib/i18n';
import PageHeader from '../../../components/PageHeader';
import TimecardSetup from './TimecardSetup';

export default async function TimecardSettingsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'OWNER') redirect('/');
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'tcSetupTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <TimecardSetup language={lang ?? 'EN'} />
    </main>
  );
}
