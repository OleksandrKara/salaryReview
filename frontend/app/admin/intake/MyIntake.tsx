'use client';

import { useEffect, useState } from 'react';
import type { Language } from '../../lib/types';
import { it, type IntakeSummary } from '../../lib/intake';
import IntakeCard from './IntakeCard';

/** Technician view: only the first-time clients assigned to her (the API enforces that). Hidden when there are none. */
export default function MyIntake({ language }: { language: Language | null }) {
  const [forms, setForms] = useState<IntakeSummary[]>([]);

  useEffect(() => {
    let alive = true;
    fetch('/api/intake-forms/upcoming?days=7', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : []))
      .then((rows: IntakeSummary[]) => { if (alive) setForms(rows); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  if (forms.length === 0) return null;
  return (
    <section data-testid="my-intake" className="mb-6 space-y-2">
      <h2 className="text-sm font-semibold text-zinc-900">{it(language, 'mineTitle')}</h2>
      <div className="grid gap-2 md:grid-cols-2">
        {forms.map((f) => (
          <div key={f.id} className="min-w-0">
            <p className="mb-1 text-sm font-medium text-zinc-900">{f.firstName ?? '•••• ' + f.phoneLast4}</p>
            <IntakeCard form={f} language={language} canManage={false} />
          </div>
        ))}
      </div>
    </section>
  );
}
