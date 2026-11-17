'use client';

import { useState } from 'react';
import type { Language } from '../../lib/types';
import { answerLabel, answerValue, intakeWhen, it, type IntakeDetail, type IntakeSummary } from '../../lib/intake';
import ConfirmSendDialog from '../messages/ConfirmSendDialog';
import IntakeBadge from './IntakeBadge';

/** One form: badge, visit, answers on demand (fetched only when opened, never cached), resend and paper receipt. */
export default function IntakeCard({ form, language, canManage, onChanged, phone = '', name = null }: {
  form: IntakeSummary;
  language: Language | null;
  canManage: boolean;
  onChanged?: () => void;
  phone?: string;
  name?: string | null;
}) {
  const [detail, setDetail] = useState<IntakeDetail | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  async function toggle() {
    if (open) { setOpen(false); setDetail(null); return; }
    setBusy(true);
    try {
      const r = await fetch(`/api/intake-forms/${form.id}`, { cache: 'no-store' });
      if (!r.ok) throw new Error(String(r.status));
      setDetail(await r.json());
      setOpen(true);
    } catch {
      setNote(it(language, 'failed'));
    } finally {
      setBusy(false);
    }
  }

  async function resend() {
    setBusy(true);
    try {
      const r = await fetch(`/api/intake-forms/${form.id}/resend`, { method: 'POST' });
      const body = await r.json().catch(() => ({}));
      const result: string | undefined = body.result;
      setNote(r.ok ? it(language, 'resendDone')
        : result === 'TOO_SOON' ? it(language, 'resendTooSoon')
        : result === 'SIGNED' ? it(language, 'resendSigned')
        : result === 'NOT_SENT' ? it(language, 'resendNotSent')
        : it(language, 'resendError'));
      onChanged?.();
    } catch {
      setNote(it(language, 'resendError'));
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  async function markPaper() {
    setBusy(true);
    try {
      await fetch(`/api/intake-forms/${form.id}/mark-received`, { method: 'POST' });
      onChanged?.();
    } finally {
      setBusy(false);
    }
  }

  const signed = form.status === 'SIGNED';
  const btn = 'min-h-9 rounded-lg px-3 text-sm font-medium ring-1 ring-zinc-300 hover:bg-zinc-50 disabled:opacity-50';

  return (
    <div data-testid="intake-card" className="rounded-xl border border-zinc-200 bg-white p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <IntakeBadge form={form} language={language} />
        <span className="text-xs text-zinc-500">{it(language, 'visit')}: {intakeWhen(form.appointmentStartAt, language)}</span>
      </div>
      {form.providerName && <p className="mt-1 text-xs text-zinc-500">{it(language, 'technician')}: {form.providerName}</p>}
      {!signed && form.sentAt && (
        <p className="mt-1 text-xs text-zinc-500">{form.openedAt ? `${it(language, 'opened')} ${intakeWhen(form.openedAt, language)}` : it(language, 'notOpened')}</p>
      )}
      <div className="mt-2 flex flex-wrap gap-2">
        {signed && form.signedVia === 'CLIENT_FORM' && (
          <button type="button" className={btn} disabled={busy} onClick={toggle} data-testid="intake-toggle">
            {open ? it(language, 'hideAnswers') : it(language, 'showAnswers')}
          </button>
        )}
        {!signed && canManage && (
          <>
            <button type="button" className={btn} disabled={busy} onClick={() => setConfirming(true)} data-testid="intake-resend">{it(language, 'resend')}</button>
            <button type="button" className={btn} disabled={busy} onClick={markPaper}>{it(language, 'markPaper')}</button>
          </>
        )}
      </div>
      {note && <p role="status" className="mt-2 text-xs text-zinc-600">{note}</p>}
      {open && detail && (
        <div data-testid="intake-answers" className="mt-3 rounded-lg bg-zinc-50 p-3">
          <p className="mb-2 text-[11px] text-zinc-500">{it(language, 'private')}</p>
          <dl className="grid gap-x-4 gap-y-2 text-sm sm:grid-cols-2">
            {detail.answers.map((a) => (
              <div key={a.key} className="min-w-0">
                <dt className="text-xs text-zinc-500">{answerLabel(language, a.key, a.label)}</dt>
                <dd className="whitespace-pre-wrap break-words font-medium text-zinc-900">{answerValue(language, a.key, a.value)}</dd>
              </div>
            ))}
          </dl>
          {detail.signatureName && <p className="mt-3 text-xs text-zinc-500">{it(language, 'signedBy')}: {detail.signatureName}</p>}
        </div>
      )}
      {confirming && (
        <ConfirmSendDialog language={language} recipientName={name ?? form.firstName} phone={phone || form.phoneLast4}
          channel="SMS" body={it(language, 'resendBody')} photoCount={0} sending={busy}
          onConfirm={resend} onCancel={() => setConfirming(false)} />
      )}
    </div>
  );
}
