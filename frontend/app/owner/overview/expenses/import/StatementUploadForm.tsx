'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '../../../../lib/api';

/** File picker + drag/drop for a bank statement CSV — uploads, then redirects straight into that
 * import's reconciliation screen on success (openspec design.md §2 UX Flow: upload -> review). */
export default function StatementUploadForm() {
  const router = useRouter();
  const [dragOver, setDragOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  async function upload(file: File) {
    if (!file.name.toLowerCase().endsWith('.csv')) {
      setError('Please choose a .csv file.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const imp = await api.uploadStatementImport(file);
      router.push(`/owner/overview/expenses/import/${imp.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to upload statement.');
      setBusy(false);
    }
  }

  return (
    <div className="mt-4">
      <div
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          const file = e.dataTransfer.files[0];
          if (file) void upload(file);
        }}
        onClick={() => inputRef.current?.click()}
        className={`flex cursor-pointer flex-col items-center gap-2 rounded-lg border-2 border-dashed p-8 text-center transition-colors ${
          dragOver ? 'border-zinc-500 bg-zinc-50' : 'border-zinc-300 hover:border-zinc-400'
        }`}
      >
        <span className="text-sm font-medium text-zinc-700">
          {busy ? 'Uploading…' : 'Tap to choose a file, or drag one here'}
        </span>
        <span className="text-xs text-zinc-400">CSV bank statement export</span>
        <input
          ref={inputRef}
          type="file"
          accept=".csv,text/csv"
          className="hidden"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void upload(file);
          }}
        />
      </div>
      {error ? <p className="mt-2 text-sm text-red-600">{error}</p> : null}
    </div>
  );
}
