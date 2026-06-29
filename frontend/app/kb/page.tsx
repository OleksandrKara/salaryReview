import Link from 'next/link';
import { serverApi } from '../lib/serverApi';
import KbManager from './KbManager';

// Knowledge base authoring. Owners/managers create and edit articles (markdown + AI drafting);
// providers get a read-only view of articles shared with them. Sync to the assistant happens on
// the RAG admin page. Role + initial (role-filtered) list are fetched server-side.
export default async function KbPage() {
  const [me, articles] = await Promise.all([serverApi.getMe(), serverApi.listKbArticles()]);
  const backHref = me.role === 'PROVIDER' ? '/me' : '/reports';

  return (
    <main className="mx-auto max-w-4xl px-4 py-10">
      <div className="mb-6 flex items-center justify-between">
        <Link href={backHref} className="text-xs text-zinc-400 hover:text-zinc-600">← Back</Link>
        <a href="/api/logout" className="text-xs text-zinc-400 hover:text-zinc-600">Log out</a>
      </div>
      <h1 className="text-lg font-semibold">Knowledge base</h1>
      <p className="mt-1 text-sm text-zinc-500">
        Service menus, scripts, and FAQ for the team.
        {me.role !== 'PROVIDER'
          ? ' Edit here; sync to the assistant from the RAG admin page.'
          : ' Read-only.'}
      </p>
      <KbManager role={me.role} language={me.preferredLanguage} initialArticles={articles} />
    </main>
  );
}
