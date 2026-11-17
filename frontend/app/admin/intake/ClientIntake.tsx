'use client';

import { useCallback, useEffect, useState } from 'react';
import type { Language } from '../../lib/types';
import { it, type IntakeSummary } from '../../lib/intake';
import IntakeCard from './IntakeCard';

/** Intake status for one client (by phone), for the messages contact panel and the lead card. Renders nothing for clients without a form. */
export default function ClientIntake({ phone, language, canManage, name = null }: {
  phone: string;
  language: Language | null;
  canManage: boolean;
  name?: string | null;
}) {
  const [forms, setForms] = useState<IntakeSummary[] | null>(null);

  const [tick, setTick] = useState(0);
  const load = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    let alive = true;
    fetch(`/api/intake-forms/by-phone?phone=${encodeURIComponent(phone)}`, { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : []))
      .catch(() => [])
      .then((rows: IntakeSummary[]) => { if (alive) setForms(rows); });
    return () => { alive = false; };
  }, [phone, tick]);

  if (!forms || forms.length === 0) return null;
  return (
    <section data-testid="client-intake" className="space-y-2">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">{it(language, 'title')}</h3>
      {forms.map((f) => (
        <IntakeCard key={f.id} form={f} language={language} canManage={canManage} onChanged={load} phone={phone} name={name} />
      ))}
    </section>
  );
}
