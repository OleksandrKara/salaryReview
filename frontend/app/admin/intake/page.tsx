import { serverApi } from '../../lib/serverApi';
import { it } from '../../lib/intake';
import PageHeader from '../../components/PageHeader';
import IntakeBoard from './IntakeBoard';

// Owner/manager: first-time clients and their intake form status. Providers see only their own
// clients on /me; the backend enforces who may see which form.
export default async function IntakePage() {
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;
  return (
    <main className="mx-auto max-w-5xl p-4 sm:p-8 2xl:max-w-7xl">
      <PageHeader title={it(lang, 'title')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <IntakeBoard language={lang} />
    </main>
  );
}
