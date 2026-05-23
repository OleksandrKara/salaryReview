import Link from 'next/link';
import { api } from '../lib/api';
import ProvidersManager from './ProvidersManager';

export default async function ProvidersPage() {
  const providers = await api.listProviders({ includeInactive: true });

  return (
    <main className="mx-auto max-w-4xl p-8">
      <div className="mb-6 flex items-baseline gap-3">
        <Link href="/" className="text-sm text-zinc-500 hover:text-zinc-700">
          ← Home
        </Link>
        <h1 className="text-2xl font-semibold">Providers</h1>
      </div>

      <ProvidersManager initialProviders={providers} />
    </main>
  );
}
