import Link from 'next/link';
import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';

// Manager home — a focused hub for the few things a manager owns: redos, the knowledge base, SOPs,
// and the floating assistant. Managers don't manage salaries, so this replaces the salary report as
// their landing (the proxy also keeps them out of owner areas). Owner/provider are redirected to
// their own homes; the proxy gates the page edge-side too.
export default async function ManagerPage() {
  const me = await serverApi.getMe();
  if (me.role === 'PROVIDER') redirect('/me');
  if (me.role === 'OWNER') redirect('/reports');
  const lang = me.preferredLanguage;

  const tiles = [
    { href: '/admin/redos', title: t(lang, 'mgrRedos'), desc: t(lang, 'mgrRedosDesc') },
    { href: '/kb', title: t(lang, 'mgrKb'), desc: t(lang, 'mgrKbDesc') },
    { href: '/sops', title: t(lang, 'mgrSops'), desc: t(lang, 'mgrSopsDesc') },
  ];

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <PageHeader title={t(lang, 'mgrTitle')} role={me.role} language={lang} />
      <p className="mt-1 text-sm text-zinc-500">{t(lang, 'mgrSubtitle')}</p>

      <div className="mt-6 grid gap-3 sm:grid-cols-3">
        {tiles.map((tile) => (
          <Link
            key={tile.href}
            href={tile.href}
            className="rounded-xl p-4 ring-1 ring-zinc-200 transition hover:ring-zinc-400"
          >
            <div className="text-sm font-semibold text-zinc-800">{tile.title}</div>
            <div className="mt-1 text-xs text-zinc-500">{tile.desc}</div>
          </Link>
        ))}
      </div>
    </main>
  );
}
