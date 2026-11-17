'use client';

import { useEffect, useState } from 'react';
import type { Language } from '../../../lib/types';
import { t } from '../../../lib/i18n';
import { type WaiverDoc, type WaiverPreview, type WaiverRow, tcFetch } from '../../../lib/timecards';

const money = (c: number) => `$${(c / 100).toFixed(2)}`;
const usd = (c: number) => `${c < 0 ? '-' : '+'}$${(Math.abs(c) / 100).toFixed(2)}`;
const BADGE: Record<WaiverRow['status'], string> = {
  NONE: 'bg-amber-100 text-amber-900',
  PENDING: 'bg-sky-100 text-sky-900',
  ACTIVE: 'bg-emerald-100 text-emerald-800',
  REVOKING: 'bg-amber-100 text-amber-900',
  REVOKED: 'bg-zinc-200 text-zinc-700',
};
const LABEL = { NONE: 'tcWvNone', PENDING: 'tcWvPending', ACTIVE: 'tcWvActive', REVOKING: 'tcWvRevoking', REVOKED: 'tcWvRevoked' } as const;
const SOURCE = { SIGNNOW: 'tcWvSourceSignnow', UPLOADED: 'tcWvSourceUploaded', WEB: 'tcWvSourceWeb' } as const;

export function WaiverBanner({ rows, lang }: { rows: WaiverRow[]; lang: Language }) {
  const missing = rows.filter((r) => r.w2 && (r.status === 'NONE' || r.status === 'REVOKED'));
  if (missing.length === 0) return null;
  return (
    <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
      <div className="font-medium">{t(lang, 'tcWvMissingBanner')}: {missing.map((m) => m.name).join(', ')}</div>
      <div className="mt-0.5 text-xs">{t(lang, 'tcWvMissingHint')}</div>
    </div>
  );
}

function PreviewBox({ p, lang }: { p: WaiverPreview; lang: Language }) {
  if (p.days.length === 0) return <div className="mt-2 text-sm text-zinc-600">{t(lang, 'tcWvNoDays')}</div>;
  return (
    <div className="mt-2 rounded border border-zinc-200 bg-zinc-50 p-2 text-sm">
      <div className="font-medium">{t(lang, 'tcWvDaysChange')}</div>
      <ul className="mt-1 flex flex-col gap-0.5 text-xs">
        {p.days.map((d) => (
          <li key={d.date} className="flex flex-wrap justify-between gap-x-3">
            <span>{d.date}{d.returnedToReview ? ` · ${t(lang, 'tcWvBackToReview')}` : ''}</span>
            <span>{money(d.premiumBeforeCents)} {'->'} {money(d.premiumAfterCents)} ({usd(d.changeCents)})</span>
          </li>
        ))}
      </ul>
      <div className="mt-1 text-xs font-medium">{t(lang, 'tcWvTotal')}: {usd(p.totalChangeCents)} · {t(lang, 'tcWvReturned')}: {p.returnedToReviewCount}</div>
    </div>
  );
}

export default function WaiverPanel({ row, lang, onChanged }: { row: WaiverRow; lang: Language; onChanged: () => void }) {
  const today = new Date().toISOString().slice(0, 10);
  const [mode, setMode] = useState<'' | 'record' | 'revoke'>('');
  const [signed, setSigned] = useState('');
  const [eff, setEff] = useState('');
  const [source, setSource] = useState('SIGNNOW');
  const [docId, setDocId] = useState('');
  const [docs, setDocs] = useState<WaiverDoc[]>([]);
  const [revFrom, setRevFrom] = useState(today);
  const [preview, setPreview] = useState<WaiverPreview | null>(null);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (mode === 'record') tcFetch<WaiverDoc[]>(`admin/waivers/${row.providerId}/documents`).then(setDocs).catch(() => setDocs([]));
  }, [mode, row.providerId]);

  const reset = () => { setMode(''); setPreview(null); setMsg(''); };
  const call = async (apply: boolean) => {
    setBusy(true);
    setMsg('');
    try {
      const body = mode === 'record'
        ? { signedDate: signed, effectiveFrom: eff || signed, documentId: docId ? Number(docId) : null, source, apply }
        : { effectiveDate: revFrom, apply };
      const r = await tcFetch<WaiverPreview>(`admin/waivers/${row.providerId}/${mode}`, 'POST', body);
      if (apply) { reset(); setMsg(t(lang, 'tcWvDone')); onChanged(); } else setPreview(r);
    } catch (e) {
      setMsg((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const field = 'rounded border border-zinc-300 px-2 py-1 text-sm';
  const canRecord = row.status === 'NONE' || row.status === 'REVOKED';
  return (
    <section className="mt-3 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{t(lang, 'tcWvTitle')}</span>
        <span className={`rounded px-2 py-0.5 text-xs ${BADGE[row.status]}`}>{t(lang, LABEL[row.status])}</span>
      </div>
      {row.signed && (
        <dl className="mt-2 grid grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
          <div><dt className="inline text-zinc-500">{t(lang, 'tcWvSigned')}: </dt><dd className="inline">{row.signedDate}</dd></div>
          <div><dt className="inline text-zinc-500">{t(lang, 'tcWvEffective')}: </dt><dd className="inline">{row.effectiveFrom}</dd></div>
          {row.revokedDate && <div><dt className="inline text-zinc-500">{t(lang, 'tcWvRevokedOn')}: </dt><dd className="inline">{row.revokedDate}</dd></div>}
          <div><dt className="inline text-zinc-500">{t(lang, 'tcWvSource')}: </dt><dd className="inline">{row.source && row.source in SOURCE ? t(lang, SOURCE[row.source as keyof typeof SOURCE]) : row.source}</dd></div>
          {row.recordedBy && <div><dt className="inline text-zinc-500">{t(lang, 'tcWvRecordedBy')}: </dt><dd className="inline">{row.recordedBy}</dd></div>}
          <div className="sm:col-span-2">
            <dt className="inline text-zinc-500">{t(lang, 'tcWvDocument')}: </dt>
            <dd className="inline">
              {row.document ? (
                <a className="text-sky-700 underline" href={`/api/owner/staff-documents/${row.document.id}/download`} target="_blank" rel="noreferrer">
                  {t(lang, 'tcWvOpenDoc')}: {row.document.documentType}{row.document.label ? ` - ${row.document.label}` : ''}
                </a>
              ) : t(lang, 'tcWvNoDocument')}
              {' · '}
              <a className="text-sky-700 underline" href="/admin/documents">{t(lang, 'tcWvManageDocs')}</a>
            </dd>
          </div>
        </dl>
      )}
      {mode === '' && (
        <div className="mt-2 flex flex-wrap gap-2">
          {canRecord && <button type="button" onClick={() => setMode('record')} className="min-h-[44px] rounded border border-zinc-400 px-3 py-1.5 sm:min-h-0">{t(lang, 'tcWvRecord')}</button>}
          {(row.status === 'ACTIVE' || row.status === 'PENDING') && <button type="button" onClick={() => setMode('revoke')} className="min-h-[44px] rounded border border-zinc-400 px-3 py-1.5 sm:min-h-0">{t(lang, 'tcWvRevoke')}</button>}
        </div>
      )}
      {mode === 'record' && (
        <div className="mt-2">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1">{t(lang, 'tcWvSignedDate')}
              <input className={field} type="date" max={today} value={signed} onChange={(e) => { setSigned(e.target.value); setPreview(null); }} />
            </label>
            <label className="flex flex-col gap-1">{t(lang, 'tcWvEffectiveFrom')}
              <input className={field} type="date" min={signed || undefined} value={eff || signed} onChange={(e) => { setEff(e.target.value); setPreview(null); }} />
            </label>
            <label className="flex flex-col gap-1">{t(lang, 'tcWvSource')}
              <select className={field} value={source} onChange={(e) => setSource(e.target.value)}>
                <option value="SIGNNOW">{t(lang, 'tcWvSourceSignnow')}</option>
                <option value="UPLOADED">{t(lang, 'tcWvSourceUploaded')}</option>
              </select>
            </label>
            <label className="flex flex-col gap-1">{t(lang, 'tcWvDocument')}
              <select className={field} value={docId} onChange={(e) => setDocId(e.target.value)}>
                <option value="">{t(lang, 'tcWvNoDocument')}</option>
                {docs.map((d) => <option key={d.id} value={d.id}>{d.documentType}{d.label ? ` - ${d.label}` : ''} ({d.fileName})</option>)}
              </select>
              {docs.length === 0 && <span className="text-xs text-zinc-500">{t(lang, 'tcWvUploadHint')}</span>}
            </label>
          </div>
        </div>
      )}
      {mode === 'revoke' && (
        <label className="mt-2 flex max-w-xs flex-col gap-1">{t(lang, 'tcWvRevokeFrom')}
          <input className={field} type="date" value={revFrom} onChange={(e) => { setRevFrom(e.target.value); setPreview(null); }} />
        </label>
      )}
      {preview && <PreviewBox p={preview} lang={lang} />}
      {mode !== '' && (
        <div className="mt-3 flex flex-wrap gap-2">
          {!preview ? (
            <button type="button" disabled={busy || (mode === 'record' ? !signed : !revFrom)} onClick={() => call(false)} className="min-h-[44px] rounded bg-zinc-900 px-3 py-1.5 text-white disabled:opacity-50 sm:min-h-0">{t(lang, 'tcWvPreview')}</button>
          ) : (
            <button type="button" disabled={busy} onClick={() => call(true)} className="min-h-[44px] rounded bg-zinc-900 px-3 py-1.5 text-white disabled:opacity-50 sm:min-h-0">{t(lang, 'tcWvApply')}</button>
          )}
          <button type="button" onClick={reset} className="min-h-[44px] rounded border border-zinc-400 px-3 py-1.5 sm:min-h-0">{t(lang, 'tcWvCancel')}</button>
        </div>
      )}
      {msg && <div className="mt-2 text-sm text-zinc-700">{msg}</div>}
    </section>
  );
}
