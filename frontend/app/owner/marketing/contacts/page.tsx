import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import ContactsFilterBar from './ContactsFilterBar';
import MarketingTabs from '../MarketingTabs';

export default async function MarketingContactsPage() {
  const [me, data] = await Promise.all([serverApi.getMe(), serverApi.getMarketingContacts()]);

  if (me?.role !== 'OWNER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing Contacts" role={me.role} language={me.preferredLanguage} />
      <MarketingTabs />

      {!data.available ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          Contact tracking isn&apos;t available yet.
        </div>
      ) : data.contacts.length === 0 ? (
        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">
          No contacts yet — one is captured automatically as soon as a visitor submits Step 1 of
          the booking form.
        </div>
      ) : (
        <div className="mt-6">
          <p className="mb-3 text-xs text-zinc-500">
            A lead is captured the moment someone submits their name and phone number, before they finish booking.
          </p>
          <ContactsFilterBar contacts={data.contacts} />
        </div>
      )}
    </main>
  );
}
