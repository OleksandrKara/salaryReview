import { serverApi } from '../../lib/serverApi';
import { t } from '../../lib/i18n';
import PageHeader from '../../components/PageHeader';
import MissedBookingManager from './MissedBookingManager';

// Owner/manager: a quick log of "a customer wanted this date/time and we had nowhere to book
// them" plus the by-month/by-weekday analysis it exists to feed — see backend V121. No Square
// connection needed (unlike /admin/redos): this never touches a real booking/order, it's tracking
// the demand that never got one.
export default async function MissedBookingsPage() {
  const me = await serverApi.getMe();
  const lang = me.preferredLanguage;

  const missedBookings = await serverApi.listMissedBookings();

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'missedBookingsTitle')} role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <p className="mb-6 text-xs text-zinc-500">{t(lang, 'missedBookingsDesc')}</p>
      <MissedBookingManager initialMissedBookings={missedBookings} language={lang} />
    </main>
  );
}
