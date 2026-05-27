'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../../lib/api';

export default function DeletePeriodButton({
  periodId,
  label,
}: {
  periodId: number;
  label: string;
}) {
  const router = useRouter();
  const [deleting, setDeleting] = useState(false);

  async function handleDelete() {
    const ok = window.confirm(
      `Delete "${label}"?\n\nAll entries for this period will be removed. This cannot be undone.`,
    );
    if (!ok) return;
    setDeleting(true);
    try {
      await api.deletePeriod(periodId);
      router.push('/');
      router.refresh();
    } catch (err) {
      alert(err instanceof Error ? err.message : String(err));
      setDeleting(false);
    }
  }

  return (
    <button
      onClick={handleDelete}
      disabled={deleting}
      data-testid="delete-period-button"
      className="ml-auto rounded border border-red-300 px-3 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
    >
      {deleting ? 'Deleting…' : 'Delete period'}
    </button>
  );
}
