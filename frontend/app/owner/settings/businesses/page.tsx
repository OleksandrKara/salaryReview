import { redirect } from 'next/navigation';
import { serverApi, ApiError } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import BusinessesPanel from './BusinessesPanel';

// Platform-admin only (PlatformBusinessController#requirePlatformAdmin) — an ordinary business's
// OWNER (e.g. AK PMU's) is still allowed at the URL level (hasRole("OWNER")) but 403s here. Found
// live 2026-08-18: AdminMenu showed this link to every OWNER regardless (now fixed to hide it for
// anyone but a platform_admin — see AdminMenu's own comment), but this page still needs its own
// graceful fallback for direct URL navigation rather than crashing on the raw 403.
//
// Lists every business and lets the owner create a new one (Phase 5.1) — a new business row plus
// its first OWNER login, so the owner can then log in as it and use the Square connection /
// business settings forms for that business specifically.
export default async function BusinessesPage() {
  let businesses;
  try {
    businesses = await serverApi.listBusinesses();
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) redirect('/');
    throw err;
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-10">
      <PageHeader title="Businesses" />
      <p className="mt-1 text-sm text-zinc-500">
        Every business on this platform. Creating one here only seeds its first owner login — you
        then log in as that business to connect its Square account and set its financial config.
      </p>
      <BusinessesPanel initialBusinesses={businesses} />
    </main>
  );
}
