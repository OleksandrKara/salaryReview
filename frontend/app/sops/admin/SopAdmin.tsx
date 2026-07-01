'use client';

import dynamic from 'next/dynamic';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import type { Sop, SopAudience, SopRosterEntry, SopVersion } from '../../lib/types';

const MDEditor = dynamic(() => import('@uiw/react-md-editor'), { ssr: false });

const AUDIENCES: SopAudience[] = ['MANAGER', 'PROVIDER', 'BOTH'];
const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '';

export default function SopAdmin({ initialSops }: { initialSops: Sop[] }) {
  const [sops, setSops] = useState<Sop[]>(initialSops);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  function upsert(s: Sop) {
    setSops((xs) => {
      const next = xs.some((x) => x.id === s.id) ? xs.map((x) => (x.id === s.id ? s : x)) : [s, ...xs];
      // Keep the list in the same onboarding order the staff see: priority, then category, then title.
      return [...next].sort(
        (a, b) => a.priority - b.priority || a.category.localeCompare(b.category) || a.title.localeCompare(b.title),
      );
    });
  }

  const selected = sops.find((s) => s.id === selectedId) ?? null;

  return (
    <div className="mt-6">
      <button
        onClick={() => { setCreating(true); setSelectedId(null); }}
        className="mb-4 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white"
      >
        New SOP
      </button>

      {creating ? (
        <CreateForm
          onCancel={() => setCreating(false)}
          onCreated={(s) => { upsert(s); setCreating(false); setSelectedId(s.id); }}
        />
      ) : null}

      <ul className="space-y-2">
        {sops.map((s) => (
          <li key={s.id} className="rounded-lg ring-1 ring-zinc-200">
            <button className="flex w-full items-center justify-between gap-3 p-3 text-left" onClick={() => { setSelectedId(selectedId === s.id ? null : s.id); setCreating(false); }}>
              <span className="flex items-center gap-2">
                {s.priority < 1000 && (
                  <span className="rounded bg-zinc-800 px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-white" title="Onboarding order (lower shows first)">
                    #{s.priority}
                  </span>
                )}
                <span>
                  <span className="text-sm font-medium text-zinc-800">{s.title}</span>
                  <span className="ml-2 text-xs text-zinc-400">{s.category}</span>
                </span>
              </span>
              <span className="flex items-center gap-1.5">
                <span className="rounded bg-zinc-100 px-1.5 py-0.5 text-[10px] text-zinc-500">{s.audience}</span>
                <span className={`rounded px-1.5 py-0.5 text-[10px] ${s.status === 'ARCHIVED' ? 'bg-zinc-100 text-zinc-500' : s.currentVersion ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}>
                  {s.status === 'ARCHIVED' ? 'Archived' : s.currentVersion ? `Live v${s.currentVersion.versionNumber}` : 'Draft'}
                </span>
              </span>
            </button>
            {selected?.id === s.id ? <SopDetail sop={s} onChanged={upsert} /> : null}
          </li>
        ))}
      </ul>
    </div>
  );
}

function CreateForm({ onCancel, onCreated }: { onCancel: () => void; onCreated: (s: Sop) => void }) {
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('');
  const [audience, setAudience] = useState<SopAudience>('PROVIDER');
  const [priority, setPriority] = useState('');
  const [body, setBody] = useState('');
  const [bodyRu, setBodyRu] = useState('');
  const [busy, setBusy] = useState(false);

  async function save() {
    if (!title.trim() || !category.trim()) return;
    setBusy(true);
    try {
      onCreated(await api.createSop({
        title: title.trim(),
        category: category.trim(),
        audience,
        priority: priority.trim() ? Number(priority) : undefined,
        body,
        bodyRu: bodyRu.trim() ? bodyRu : null,
      }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mb-4 space-y-3 rounded-lg p-4 ring-1 ring-zinc-200">
      <h2 className="text-sm font-semibold">New SOP</h2>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title" className="rounded px-2 py-1 text-sm ring-1 ring-zinc-200" />
        <input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="Category" className="rounded px-2 py-1 text-sm ring-1 ring-zinc-200" />
        <select value={audience} onChange={(e) => setAudience(e.target.value as SopAudience)} className="rounded px-2 py-1 text-sm ring-1 ring-zinc-200">
          {AUDIENCES.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <input
          type="number"
          value={priority}
          onChange={(e) => setPriority(e.target.value)}
          placeholder="Order (e.g. 1)"
          title="Onboarding order — lower shows first. Leave blank to sort after prioritized SOPs."
          className="rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
        />
      </div>
      <BilingualBody body={body} bodyRu={bodyRu} onBody={setBody} onBodyRu={setBodyRu} height={280} />
      <div className="flex gap-2">
        <button onClick={save} disabled={busy} className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40">
          {busy ? <Spinner className="h-4 w-4 text-white" /> : null}
          Create draft
        </button>
        <button onClick={onCancel} className="rounded-lg px-4 py-2 text-sm ring-1 ring-zinc-200">Cancel</button>
      </div>
    </div>
  );
}

function SopDetail({ sop, onChanged }: { sop: Sop; onChanged: (s: Sop) => void }) {
  const [versions, setVersions] = useState<SopVersion[] | null>(null);
  const [roster, setRoster] = useState<SopRosterEntry[] | null>(null);
  const [audience, setAudience] = useState<SopAudience>(sop.audience);
  const [priority, setPriority] = useState(String(sop.priority));
  const [newDraft, setNewDraft] = useState<string | null>(null);
  const [newDraftRu, setNewDraftRu] = useState('');
  const [compare, setCompare] = useState<SopVersion | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.listSopVersions(sop.id).then(setVersions).catch(() => setVersions([]));
    api.sopRoster(sop.id).then(setRoster).catch(() => setRoster([]));
  }, [sop.id]);
  useEffect(() => { load(); }, [load]);

  async function saveMeta() {
    setBusy(true);
    try {
      onChanged(await api.updateSop(sop.id, {
        title: sop.title, category: sop.category, audience,
        priority: priority.trim() ? Number(priority) : sop.priority,
      }));
    } finally { setBusy(false); }
  }
  async function addDraft() {
    if (newDraft == null) return;
    setBusy(true);
    try {
      await api.addSopVersion(sop.id, newDraft, newDraftRu.trim() ? newDraftRu : null);
      setNewDraft(null);
      setNewDraftRu('');
      load();
    } finally { setBusy(false); }
  }
  async function doPublish(v: SopVersion) {
    setBusy(true);
    try { onChanged(await api.publishSopVersion(sop.id, v.id)); setCompare(null); load(); }
    finally { setBusy(false); }
  }
  async function toggleArchive() {
    setBusy(true);
    try { onChanged(sop.status === 'ARCHIVED' ? await api.unarchiveSop(sop.id) : await api.archiveSop(sop.id)); }
    finally { setBusy(false); }
  }

  const currentBody = sop.currentVersion?.body ?? '';
  const widening = (audience === 'BOTH' && sop.audience !== 'BOTH');

  return (
    <div className="space-y-5 border-t border-zinc-100 p-4">
      {/* audience + archive */}
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs font-medium text-zinc-600">
          Audience
          <select value={audience} onChange={(e) => setAudience(e.target.value as SopAudience)} className="ml-2 rounded px-2 py-1 text-sm ring-1 ring-zinc-200">
            {AUDIENCES.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
        </label>
        <label className="text-xs font-medium text-zinc-600" title="Onboarding order — lower shows first">
          Order
          <input type="number" value={priority} onChange={(e) => setPriority(e.target.value)} className="ml-2 w-20 rounded px-2 py-1 text-sm tabular-nums ring-1 ring-zinc-200" />
        </label>
        {(audience !== sop.audience || priority !== String(sop.priority)) ? (
          <button onClick={saveMeta} disabled={busy} className="rounded px-2 py-1 text-xs ring-1 ring-zinc-200">Save</button>
        ) : null}
        <button onClick={toggleArchive} disabled={busy} className="ml-auto rounded px-2 py-1 text-xs ring-1 ring-zinc-200">
          {sop.status === 'ARCHIVED' ? 'Unarchive' : 'Archive'}
        </button>
      </div>
      {widening ? <p className="text-xs text-amber-600">Widening to Both means providers will now see this SOP and be asked to acknowledge it.</p> : null}

      {/* versions */}
      <div>
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Versions</h3>
        {versions === null ? (
          <Spinner className="h-4 w-4 text-zinc-400" />
        ) : (
          <ul className="space-y-1">
            {versions.map((v) => (
              <li key={v.id} className="flex items-center justify-between rounded px-2 py-1 text-sm ring-1 ring-zinc-100">
                <span>
                  v{v.versionNumber}
                  <span className="ml-2 text-[10px] text-zinc-400">{v.status}{sop.currentVersion?.id === v.id ? ' · LIVE' : ''}</span>
                </span>
                {v.status === 'DRAFT' ? (
                  <button onClick={() => setCompare(v)} className="rounded px-2 py-0.5 text-xs ring-1 ring-zinc-200">Publish…</button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {newDraft == null ? (
          <button
            onClick={() => { setNewDraft(currentBody); setNewDraftRu(sop.currentVersion?.bodyRu ?? ''); }}
            className="mt-2 rounded px-2 py-1 text-xs ring-1 ring-zinc-200"
          >
            New draft
          </button>
        ) : (
          <div className="mt-2 space-y-2">
            <BilingualBody body={newDraft} bodyRu={newDraftRu} onBody={(v) => setNewDraft(v)} onBodyRu={setNewDraftRu} height={260} />
            <div className="flex gap-2">
              <button onClick={addDraft} disabled={busy} className="rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40">Save draft</button>
              <button onClick={() => setNewDraft(null)} className="rounded-lg px-3 py-1.5 text-xs ring-1 ring-zinc-200">Cancel</button>
            </div>
          </div>
        )}
      </div>

      {/* publish compare */}
      {compare ? (
        <div className="space-y-2 rounded-lg bg-zinc-50 p-3">
          <h3 className="text-xs font-semibold">Publish v{compare.versionNumber} — compare</h3>
          <div className="grid grid-cols-2 gap-3 text-xs">
            <div>
              <div className="mb-1 text-[10px] uppercase text-zinc-400">Current live{sop.currentVersion ? ` (v${sop.currentVersion.versionNumber})` : ' (none)'}</div>
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-white p-2 ring-1 ring-zinc-200">{currentBody || '(none)'}</pre>
            </div>
            <div>
              <div className="mb-1 text-[10px] uppercase text-zinc-400">New (v{compare.versionNumber})</div>
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-white p-2 ring-1 ring-zinc-200">{compare.body || '(empty)'}</pre>
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={() => doPublish(compare)} disabled={busy} className="rounded-lg bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40">Confirm publish</button>
            <button onClick={() => setCompare(null)} className="rounded-lg px-3 py-1.5 text-xs ring-1 ring-zinc-200">Cancel</button>
          </div>
          <p className="text-[10px] text-zinc-400">Publishing resets acknowledgment — everyone in the audience will need to acknowledge the new version.</p>
        </div>
      ) : null}

      {/* roster */}
      <div>
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">Acknowledgment roster</h3>
        {roster === null ? (
          <Spinner className="h-4 w-4 text-zinc-400" />
        ) : roster.length === 0 ? (
          <p className="text-xs text-zinc-400">No users in this audience.</p>
        ) : (
          <ul className="space-y-1">
            {roster.map((r) => (
              <li key={r.userId} className="flex items-center justify-between rounded px-2 py-1 text-sm ring-1 ring-zinc-100">
                <span>{r.username} <span className="text-[10px] text-zinc-400">{r.role}</span></span>
                {r.acknowledged ? (
                  <span className="text-xs text-green-700">✅ {fmt(r.acknowledgedAt)}</span>
                ) : (
                  <span className="text-xs text-zinc-400">not acknowledged</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

// English/Русский tabs for a SOP version body, with one-click AI translation of the English content.
// Reused by the create form and the new-draft editor.
function BilingualBody({
  body,
  bodyRu,
  onBody,
  onBodyRu,
  height = 280,
}: {
  body: string;
  bodyRu: string;
  onBody: (v: string) => void;
  onBodyRu: (v: string) => void;
  height?: number;
}) {
  const [lang, setLang] = useState<'EN' | 'RU'>('EN');
  const [translating, setTranslating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function translate() {
    if (!body.trim()) {
      setError('Add English content first, then translate.');
      return;
    }
    setTranslating(true);
    setError(null);
    try {
      const { markdown } = await api.aiTranslateSop(body);
      onBodyRu(markdown);
    } catch {
      setError('AI translation is unavailable right now.');
    } finally {
      setTranslating(false);
    }
  }

  const tab = (l: 'EN' | 'RU') =>
    `rounded px-2 py-1 text-xs ${lang === l ? 'bg-zinc-900 text-white' : 'ring-1 ring-zinc-200 text-zinc-600'}`;

  return (
    <div>
      <div className="mb-2 flex items-center gap-2">
        <button type="button" onClick={() => setLang('EN')} className={tab('EN')}>English</button>
        <button type="button" onClick={() => setLang('RU')} className={tab('RU')}>Русский</button>
        {lang === 'RU' ? (
          <button
            type="button"
            onClick={translate}
            disabled={translating || !body.trim()}
            className="ml-auto inline-flex items-center gap-1.5 rounded bg-zinc-100 px-3 py-1 text-xs disabled:opacity-50"
          >
            {translating ? <Spinner className="h-3.5 w-3.5 text-zinc-400" /> : null}
            Translate from English
          </button>
        ) : null}
      </div>
      <div data-color-mode="light">
        {lang === 'EN' ? (
          <MDEditor value={body} onChange={(v) => onBody(v ?? '')} height={height} />
        ) : (
          <MDEditor value={bodyRu} onChange={(v) => onBodyRu(v ?? '')} height={height} />
        )}
      </div>
      {error ? <p className="mt-1 text-xs text-red-600">{error}</p> : null}
      {lang === 'RU' ? <p className="mt-1 text-[10px] text-zinc-400">Readers see English when this is empty.</p> : null}
    </div>
  );
}
