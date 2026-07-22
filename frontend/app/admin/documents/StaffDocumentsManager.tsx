'use client';

import { useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import type { AppUser, Provider, StaffDocument, StaffDocumentExpirationStatus } from '../../lib/types';

const DOCUMENT_TYPE_SUGGESTIONS = ['Contract', 'License', 'NDA', 'Insurance', 'Certification'];

const fmtDate = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
};

function StatusBadge({ status }: { status: StaffDocumentExpirationStatus }) {
  const style = {
    OK: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    EXPIRING_SOON: 'bg-amber-50 text-amber-700 ring-amber-200',
    EXPIRED: 'bg-red-50 text-red-700 ring-red-200',
  }[status];
  const label = { OK: 'Valid', EXPIRING_SOON: 'Expiring soon', EXPIRED: 'Expired' }[status];
  return (
    <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${style}`}>
      {label}
    </span>
  );
}

type Person = { type: 'PROVIDER' | 'MANAGER'; id: number; name: string };

export default function StaffDocumentsManager({
  initialDocuments,
  providers,
  managers,
}: {
  initialDocuments: StaffDocument[];
  providers: Provider[];
  managers: AppUser[];
}) {
  const [documents, setDocuments] = useState<StaffDocument[]>(initialDocuments);
  const [uploadingFor, setUploadingFor] = useState<Person | null>(null);
  const [error, setError] = useState<string | null>(null);

  const people: Person[] = useMemo(() => [
    ...providers.map((p) => ({ type: 'PROVIDER' as const, id: p.id, name: p.displayName })),
    ...managers.map((u) => ({ type: 'MANAGER' as const, id: u.id, name: u.username })),
  ], [providers, managers]);

  const docsFor = (person: Person) =>
    documents
      .filter((d) => d.personType === person.type && d.personId === person.id)
      .sort((a, b) => a.expirationDate.localeCompare(b.expirationDate));

  const expiringCount = documents.filter((d) => d.status === 'EXPIRING_SOON').length;
  const expiredCount = documents.filter((d) => d.status === 'EXPIRED').length;

  async function remove(d: StaffDocument) {
    if (!confirm(`Delete "${d.documentType}${d.label ? ` — ${d.label}` : ''}" for ${d.personName}?`)) return;
    try {
      await api.deleteStaffDocument(d.id);
      setDocuments((xs) => xs.filter((x) => x.id !== d.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed.');
    }
  }

  function onUploaded(saved: StaffDocument) {
    setDocuments((xs) => [...xs, saved]);
    setUploadingFor(null);
  }

  return (
    <div>
      {(expiredCount > 0 || expiringCount > 0) && (
        <div className="mb-6 flex flex-wrap gap-2">
          {expiredCount > 0 && (
            <span className="inline-flex items-center gap-1.5 rounded-lg bg-red-50 px-3 py-1.5 text-sm font-medium text-red-700 ring-1 ring-red-200">
              ⚠ {expiredCount} document{expiredCount === 1 ? '' : 's'} expired — request a new copy
            </span>
          )}
          {expiringCount > 0 && (
            <span className="inline-flex items-center gap-1.5 rounded-lg bg-amber-50 px-3 py-1.5 text-sm font-medium text-amber-700 ring-1 ring-amber-200">
              {expiringCount} expiring within 30 days
            </span>
          )}
        </div>
      )}

      {error ? <p className="mb-3 text-sm text-red-600">{error}</p> : null}

      <PersonGroup
        title="Service Providers"
        people={people.filter((p) => p.type === 'PROVIDER')}
        docsFor={docsFor}
        uploadingFor={uploadingFor}
        setUploadingFor={setUploadingFor}
        onUploaded={onUploaded}
        onRemove={remove}
      />
      <PersonGroup
        title="Managers"
        people={people.filter((p) => p.type === 'MANAGER')}
        docsFor={docsFor}
        uploadingFor={uploadingFor}
        setUploadingFor={setUploadingFor}
        onUploaded={onUploaded}
        onRemove={remove}
      />
    </div>
  );
}

function PersonGroup({
  title, people, docsFor, uploadingFor, setUploadingFor, onUploaded, onRemove,
}: {
  title: string;
  people: Person[];
  docsFor: (p: Person) => StaffDocument[];
  uploadingFor: Person | null;
  setUploadingFor: (p: Person | null) => void;
  onUploaded: (d: StaffDocument) => void;
  onRemove: (d: StaffDocument) => void;
}) {
  if (people.length === 0) return null;
  return (
    <div className="mb-8">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">
        {title} <span className="font-normal normal-case text-zinc-400">({people.length})</span>
      </h3>
      <ul className="space-y-2">
        {people.map((person) => {
          const docs = docsFor(person);
          const isUploading = uploadingFor?.type === person.type && uploadingFor?.id === person.id;
          return (
            <li key={`${person.type}-${person.id}`} className="rounded-lg p-3 ring-1 ring-zinc-200">
              <div className="flex items-center justify-between gap-3">
                <span className="text-sm font-medium text-zinc-800">{person.name}</span>
                <button
                  onClick={() => setUploadingFor(isUploading ? null : person)}
                  className="rounded px-2 py-1 text-xs ring-1 ring-zinc-200 hover:bg-zinc-50"
                >
                  {isUploading ? 'Cancel' : '+ Add document'}
                </button>
              </div>

              {docs.length === 0 && !isUploading ? (
                <p className="mt-2 text-xs text-zinc-400">No documents on file.</p>
              ) : (
                <ul className="mt-2 space-y-1.5">
                  {docs.map((d) => (
                    <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 rounded bg-zinc-50 px-2.5 py-1.5">
                      <div className="min-w-0">
                        <span className="text-sm font-medium text-zinc-700">{d.documentType}</span>
                        {d.label ? <span className="ml-1.5 text-xs text-zinc-500">{d.label}</span> : null}
                        <div className="text-xs text-zinc-400">Expires {fmtDate(d.expirationDate)}</div>
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <StatusBadge status={d.status} />
                        <a
                          href={api.staffDocumentDownloadUrl(d.id)}
                          className="text-xs font-medium text-blue-600 hover:underline"
                        >
                          Download
                        </a>
                        <button onClick={() => onRemove(d)} className="text-xs font-medium text-red-600 hover:underline">
                          Delete
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}

              {isUploading && <UploadForm person={person} onUploaded={onUploaded} onCancel={() => setUploadingFor(null)} />}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function UploadForm({
  person, onUploaded, onCancel,
}: {
  person: Person;
  onUploaded: (d: StaffDocument) => void;
  onCancel: () => void;
}) {
  const [documentType, setDocumentType] = useState('');
  const [label, setLabel] = useState('');
  const [expirationDate, setExpirationDate] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!documentType.trim() || !expirationDate || !file) {
      setError('Document type, expiration date, and a file are all required.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = await api.createStaffDocument({
        file,
        providerId: person.type === 'PROVIDER' ? person.id : undefined,
        appUserId: person.type === 'MANAGER' ? person.id : undefined,
        documentType: documentType.trim(),
        label: label.trim() || undefined,
        expirationDate,
      });
      onUploaded(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Upload failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-3 space-y-2 border-t border-zinc-100 pt-3">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        <label className="block text-xs font-medium text-zinc-600">
          Document type
          <input
            list="staff-document-types"
            value={documentType}
            onChange={(e) => setDocumentType(e.target.value)}
            placeholder="Contract, License, NDA…"
            className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
          />
          <datalist id="staff-document-types">
            {DOCUMENT_TYPE_SUGGESTIONS.map((t) => <option key={t} value={t} />)}
          </datalist>
        </label>
        <label className="block text-xs font-medium text-zinc-600">
          Label (optional)
          <input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="e.g. Cosmetology — CA"
            className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
          />
        </label>
        <label className="block text-xs font-medium text-zinc-600">
          Expiration date
          <input
            type="date"
            value={expirationDate}
            onChange={(e) => setExpirationDate(e.target.value)}
            className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
          />
        </label>
      </div>
      <input
        type="file"
        onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        className="block w-full text-xs text-zinc-600"
      />
      {error ? <p className="text-xs text-red-600">{error}</p> : null}
      <div className="flex gap-2">
        <button
          onClick={submit}
          disabled={busy}
          className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40"
        >
          {busy ? <Spinner className="h-3.5 w-3.5 text-white" /> : null}
          Upload
        </button>
        <button onClick={onCancel} disabled={busy} className="rounded-lg px-3 py-1.5 text-xs ring-1 ring-zinc-200">
          Cancel
        </button>
      </div>
    </div>
  );
}
