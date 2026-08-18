import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import ContactsFilterBar from './ContactsFilterBar';
import MarketingTabs from '../MarketingTabs';

export default async function MarketingContactsPage({
  searchParams,
}: {
  searchParams: Promise<{ slug?: string }>;
}) {
  // getBusinessSettings is OWNER-only (403 for ADS_MANAGER) — fails open, same reasoning as the
  // Overview tab's own page.tsx: an ADS_MANAGER still gets the contacts list, just falling back
  // to period.ts's own default timezone rather than this business's real configured one.
  const [{ slug }, me, data, businessSettings] = await Promise.all([
    searchParams,
    serverApi.getMe(),
    serverApi.getMarketingContacts(),
    serverApi.getBusinessSettings().catch(() => null),
  ]);
  const timeZone = businessSettings?.timezone;

  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-8">
      <PageHeader title="Marketing Contacts" role={me.role} language={me.preferredLanguage} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
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
          {/* Keyed by slug so switching pages via the shared selector — a client-side navigation
              that wouldn't otherwise remount this component — actually re-applies the "Landing
              page" facet default instead of leaving it at whatever it was already set to. */}
          <ContactsFilterBar key={slug ?? 'default'} contacts={data.contacts} initialLandingPage={slug} timeZone={timeZone} />
        </div>
      )}
    </main>
  );
}
