import { redirect } from 'next/navigation';
import { serverApi } from '../lib/serverApi';
import { t } from '../lib/i18n';
import PageHeader from '../components/PageHeader';
import type { Language, StaffDocumentExpirationStatus } from '../lib/types';

const fmtDate = (iso: string, lang: Language | null) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    month: 'short', day: 'numeric', year: 'numeric',
  });
};

function statusStyle(status: StaffDocumentExpirationStatus): string {
  return {
    OK: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    EXPIRING_SOON: 'bg-amber-50 text-amber-700 ring-amber-200',
    EXPIRED: 'bg-red-50 text-red-700 ring-red-200',
  }[status];
}

function statusLabel(status: StaffDocumentExpirationStatus, lang: Language | null): string {
  const key = {
    OK: 'myDocumentsStatusOk',
    EXPIRING_SOON: 'myDocumentsStatusExpiringSoon',
    EXPIRED: 'myDocumentsStatusExpired',
  }[status] as Parameters<typeof t>[1];
  return t(lang, key);
}

// A provider/manager's own read-only view of their staff documents — view and download only, see
// StaffDocumentSelfController. Owners manage documents (upload/edit/delete) on /admin/documents
// instead; this page never exposes those actions.
export default async function MyDocumentsPage() {
  const me = await serverApi.getMe();
  if (me.role !== 'PROVIDER' && me.role !== 'MANAGER') redirect('/');

  const lang = me.preferredLanguage;
  const documents = await serverApi.getMyStaffDocuments();
  const sorted = [...documents].sort((a, b) => a.expirationDate.localeCompare(b.expirationDate));

  return (
    <main className="mx-auto max-w-2xl p-4 sm:p-8">
      <PageHeader title={t(lang, 'myDocumentsTitle')} role={me.role} language={lang} />
      <p className="-mt-3 mb-6 text-sm text-zinc-500">{t(lang, 'myDocumentsSubtitle')}</p>

      {sorted.length === 0 ? (
        <p className="text-sm text-zinc-400">{t(lang, 'myDocumentsEmpty')}</p>
      ) : (
        <ul className="space-y-2">
          {sorted.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-zinc-50 px-3 py-2.5 ring-1 ring-zinc-200">
              <div className="min-w-0">
                <span className="text-sm font-medium text-zinc-700">{d.documentType}</span>
                {d.label ? <span className="ml-1.5 text-xs text-zinc-500">{d.label}</span> : null}
                <div className="text-xs text-zinc-400">
                  {t(lang, 'myDocumentsExpiresOn')} {fmtDate(d.expirationDate, lang)}
                </div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${statusStyle(d.status)}`}>
                  {statusLabel(d.status, lang)}
                </span>
                <a
                  href={`/api/staff-documents/me/${d.id}/download`}
                  className="text-xs font-medium text-blue-600 hover:underline"
                >
                  {t(lang, 'myDocumentsDownload')}
                </a>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
