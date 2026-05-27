'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from './lib/api';
import type { PayPeriod } from './lib/types';

export default function PeriodRow({ period }: { period: PayPeriod }) {
  const router = useRouter();
  const [deleting, setDeleting] = useState(false);

  async function handleDelete(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    const ok = window.confirm(
      `Delete "${period.label}"?\n\nAll entries for this period will be removed. This cannot be undone.`,
    );
    if (!ok) return;
    setDeleting(true);
    try {
      await api.deletePeriod(period.id);
      router.refresh();
    } catch (err) {
      alert(err instanceof Error ? err.message : String(err));
      setDeleting(false);
    }
  }

  return (
    <li data-testid={`period-row-${period.id}`} className="flex items-center">
      <Link
        href={`/periods/${period.id}`}
        data-testid={`period-link-${period.id}`}
        className="flex-1 px-4 py-3 hover:bg-zinc-50"
      >
        <span className="font-medium">{period.label}</span>
        <span className="ml-2 text-sm text-zinc-500">#{period.id}</span>
      </Link>
      <button
        onClick={handleDelete}
        disabled={deleting}
        title="Delete this period"
        data-testid={`period-delete-${period.id}`}
        className="px-3 py-3 text-zinc-400 hover:text-red-600 disabled:opacity-50"
      >
        {deleting ? '…' : '✕'}
      </button>
    </li>
  );
}
