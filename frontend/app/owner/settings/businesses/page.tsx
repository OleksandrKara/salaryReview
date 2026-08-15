import { serverApi } from '../../../lib/serverApi';
import PageHeader from '../../../components/PageHeader';
import BusinessesPanel from './BusinessesPanel';

// Owner-only. Lists every business and lets the owner create a new one (Phase 5.1) — a new
// business row plus its first OWNER login, so the owner can then log in as it and use the Square
// connection / business settings forms for that business specifically.
export default async function BusinessesPage() {
  const businesses = await serverApi.listBusinesses();

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
