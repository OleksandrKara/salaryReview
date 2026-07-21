'use client';

import dynamic from 'next/dynamic';
import { useState } from 'react';
import { api } from '../lib/api';
import { Spinner } from '../components/Spinner';
import ShareLinkButton from '../components/ShareLinkButton';
import KbArticleBody from './KbArticleBody';
import { SyncBadge } from './KbSyncBadge';
import { localized } from '../lib/i18n';
import type { KbArticle, Language, Role } from '../lib/types';

// @uiw/react-md-editor touches `window`, so load it client-only.
const MDEditor = dynamic(() => import('@uiw/react-md-editor'), { ssr: false });

type Draft = {
  id: number | null;
  title: string;
  titleRu: string;
  category: string;
  body: string;
  bodyRu: string;
  providerVisible: boolean;
};

const emptyDraft = (): Draft => ({ id: null, title: '', titleRu: '', category: '', body: '', bodyRu: '', providerVisible: false });

// Owner-only grouping (see SopList for the same idea on SOPs) — visibleRoles always includes
// OWNER+MANAGER (see KbArticleService#normalizeRoles), so "shared with providers or not" is the
// one real audience distinction for KB articles.
const VISIBILITY_GROUPS: { forProvider: boolean; label: string }[] = [
  { forProvider: false, label: 'Manager only' },
  { forProvider: true, label: 'Manager & Service Provider' },
];

export default function KbManager({
  role,
  language,
  initialArticles,
}: {
  role: Role;
  language: Language | null;
  initialArticles: KbArticle[];
}) {
  const isAdmin = role !== 'PROVIDER';
  const [articles, setArticles] = useState<KbArticle[]>(initialArticles);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [viewing, setViewing] = useState<KbArticle | null>(null);

  function startNew() {
    setViewing(null);
    setDraft(emptyDraft());
  }
  function startEdit(a: KbArticle) {
    setViewing(null);
    setDraft({
      id: a.id,
      title: a.title,
      titleRu: a.titleRu ?? '',
      category: a.category,
      body: a.body,
      bodyRu: a.bodyRu ?? '',
      providerVisible: a.visibleRoles.includes('PROVIDER'),
    });
  }

  async function remove(a: KbArticle) {
    if (!confirm(`Delete “${localized(language, a.title, a.titleRu)}”? Its synced copy in the assistant will also be removed.`)) return;
    await api.deleteKbArticle(a.id);
    setArticles((xs) => xs.filter((x) => x.id !== a.id));
  }

  function onSaved(saved: KbArticle) {
    setArticles((xs) => {
      const i = xs.findIndex((x) => x.id === saved.id);
      if (i === -1) return [...xs, saved];
      const copy = [...xs];
      copy[i] = saved;
      return copy;
    });
    setDraft(null);
  }

  if (draft) {
    return <Editor draft={draft} onCancel={() => setDraft(null)} onSaved={onSaved} />;
  }

  return (
    <div className="mt-6">
      {isAdmin ? (
        <button
          onClick={startNew}
          className="mb-4 rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white"
        >
          New article
        </button>
      ) : null}

      {articles.length === 0 ? (
        <p className="rounded-lg px-4 py-3 text-sm text-zinc-400 ring-1 ring-zinc-200">No articles yet.</p>
      ) : role === 'OWNER' ? (
        <div className="space-y-6">
          {VISIBILITY_GROUPS.map(({ forProvider, label }) => {
            const group = articles.filter((a) => a.visibleRoles.includes('PROVIDER') === forProvider);
            if (group.length === 0) return null;
            return (
              <div key={label}>
                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-zinc-500">
                  {label} <span className="font-normal normal-case text-zinc-400">({group.length})</span>
                </h3>
                <ul className="space-y-2">
                  {group.map((a) => (
                    <ArticleRow key={a.id} a={a} language={language} isAdmin={isAdmin} viewing={viewing} setViewing={setViewing} startEdit={startEdit} remove={remove} />
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      ) : (
        <ul className="space-y-2">
          {articles.map((a) => (
            <ArticleRow key={a.id} a={a} language={language} isAdmin={isAdmin} viewing={viewing} setViewing={setViewing} startEdit={startEdit} remove={remove} />
          ))}
        </ul>
      )}
    </div>
  );
}

function ArticleRow({
  a, language, isAdmin, viewing, setViewing, startEdit, remove,
}: {
  a: KbArticle;
  language: Language | null;
  isAdmin: boolean;
  viewing: KbArticle | null;
  setViewing: (a: KbArticle | null) => void;
  startEdit: (a: KbArticle) => void;
  remove: (a: KbArticle) => void;
}) {
  return (
    <li className="rounded-lg p-3 ring-1 ring-zinc-200">
      <div className="flex items-start justify-between gap-3">
        <button className="text-left" onClick={() => setViewing(viewing?.id === a.id ? null : a)}>
          <span className="text-sm font-medium text-zinc-800">{localized(language, a.title, a.titleRu)}</span>
          <span className="ml-2 text-xs text-zinc-400">{a.category}</span>
          <div className="mt-1 flex items-center gap-1.5">
            <SyncBadge status={a.syncStatus} />
            {a.bodyRu ? (
              <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-[10px] text-indigo-700">RU</span>
            ) : null}
            {a.visibleRoles.map((r) => (
              <span key={r} className="rounded bg-zinc-100 px-1.5 py-0.5 text-[10px] text-zinc-500">{r}</span>
            ))}
          </div>
        </button>
        <div className="flex shrink-0 gap-2">
          <ShareLinkButton path={`/kb/${a.id}`} title={localized(language, a.title, a.titleRu)} />
          {isAdmin ? (
            <>
              <button onClick={() => startEdit(a)} className="rounded px-2 py-1 text-xs ring-1 ring-zinc-200">Edit</button>
              <button onClick={() => remove(a)} className="rounded px-2 py-1 text-xs text-red-600 ring-1 ring-red-200">Delete</button>
            </>
          ) : null}
        </div>
      </div>
      {viewing?.id === a.id ? (
        <div className="mt-3 border-t border-zinc-100 pt-3">
          <KbArticleBody article={a} defaultLang={language ?? 'EN'} />
        </div>
      ) : null}
    </li>
  );
}

function Editor({
  draft,
  onCancel,
  onSaved,
}: {
  draft: Draft;
  onCancel: () => void;
  onSaved: (a: KbArticle) => void;
}) {
  const [title, setTitle] = useState(draft.title);
  const [titleRu, setTitleRu] = useState(draft.titleRu);
  const [category, setCategory] = useState(draft.category);
  const [body, setBody] = useState(draft.body);
  const [bodyRu, setBodyRu] = useState(draft.bodyRu);
  const [editLang, setEditLang] = useState<Language>('EN');
  const [providerVisible, setProviderVisible] = useState(draft.providerVisible);
  const [aiPrompt, setAiPrompt] = useState('');
  const [busy, setBusy] = useState<'save' | 'ai' | 'translate' | 'translate-title' | null>(null);
  const [error, setError] = useState<string | null>(null);

  function visibleRoles(): Role[] {
    return providerVisible ? ['OWNER', 'MANAGER', 'PROVIDER'] : ['OWNER', 'MANAGER'];
  }

  async function save() {
    if (!title.trim() || !category.trim()) {
      setError('Title and category are required.');
      return;
    }
    setBusy('save');
    setError(null);
    try {
      const payload = {
        title: title.trim(),
        titleRu: titleRu.trim() ? titleRu : null,
        category: category.trim(),
        body,
        bodyRu: bodyRu.trim() ? bodyRu : null,
        visibleRoles: visibleRoles(),
      };
      const saved = draft.id == null
        ? await api.createKbArticle(payload)
        : await api.updateKbArticle(draft.id, payload);
      onSaved(saved);
    } catch {
      setError('Save failed. Please try again.');
    } finally {
      setBusy(null);
    }
  }

  async function draftWithAi() {
    if (!aiPrompt.trim()) return;
    setBusy('ai');
    setError(null);
    try {
      const { markdown } = await api.aiDraftKbArticle(aiPrompt.trim(), body || null);
      setBody(markdown);
    } catch {
      setError('AI drafting is unavailable right now.');
    } finally {
      setBusy(null);
    }
  }

  async function translate() {
    if (!body.trim()) {
      setError('Add English content first, then translate.');
      return;
    }
    setBusy('translate');
    setError(null);
    try {
      const { markdown } = await api.aiTranslateKbArticle(body);
      setBodyRu(markdown);
    } catch {
      setError('AI translation is unavailable right now.');
    } finally {
      setBusy(null);
    }
  }

  async function translateTitle() {
    if (!title.trim()) {
      setError('Add an English title first, then translate.');
      return;
    }
    setBusy('translate-title');
    setError(null);
    try {
      const { markdown } = await api.aiTranslateKbNote(title);
      setTitleRu(markdown);
    } catch {
      setError('AI translation is unavailable right now.');
    } finally {
      setBusy(null);
    }
  }

  const tab = (l: Language) =>
    `rounded px-2 py-1 text-xs ${editLang === l ? 'bg-zinc-900 text-white' : 'ring-1 ring-zinc-200 text-zinc-600'}`;

  return (
    <div className="mt-6 space-y-4">
      <h2 className="text-sm font-semibold">{draft.id == null ? 'New article' : 'Edit article'}</h2>

      <div className="grid grid-cols-2 gap-3">
        <label className="block text-xs font-medium text-zinc-600">
          Title
          <input value={title} onChange={(e) => setTitle(e.target.value)} className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200" />
        </label>
        <label className="block text-xs font-medium text-zinc-600">
          Category
          <input value={category} onChange={(e) => setCategory(e.target.value)} placeholder="e.g. Booking Scripts" className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200" />
        </label>
      </div>

      <div className="rounded-lg p-3 ring-1 ring-zinc-200">
        <div className="mb-2 flex items-center gap-2">
          <button type="button" onClick={() => setEditLang('EN')} className={tab('EN')}>English</button>
          <button type="button" onClick={() => setEditLang('RU')} className={tab('RU')}>Русский</button>
        </div>

        {editLang === 'EN' ? (
          <>
            <div className="mb-2 flex items-center gap-2">
              <input
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                placeholder="Draft with AI — e.g. “a friendly late-cancellation script”"
                className="flex-1 rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
              />
              <button onClick={draftWithAi} disabled={busy !== null || !aiPrompt.trim()} className="inline-flex items-center gap-1.5 rounded bg-zinc-100 px-3 py-1 text-xs disabled:opacity-50">
                {busy === 'ai' ? <Spinner className="h-3.5 w-3.5 text-zinc-400" /> : null}
                Draft with AI
              </button>
            </div>
            <div data-color-mode="light">
              <MDEditor value={body} onChange={(v) => setBody(v ?? '')} height={320} />
            </div>
          </>
        ) : (
          <>
            <div className="mb-3 flex items-end gap-2">
              <label className="block flex-1 text-xs font-medium text-zinc-600">
                Title (Russian) — readers see the English title when this is empty
                <input
                  value={titleRu}
                  onChange={(e) => setTitleRu(e.target.value)}
                  className="mt-1 w-full rounded px-2 py-1 text-sm ring-1 ring-zinc-200"
                />
              </label>
              <button onClick={translateTitle} disabled={busy !== null || !title.trim()} className="inline-flex shrink-0 items-center gap-1.5 rounded bg-zinc-100 px-3 py-1.5 text-xs disabled:opacity-50">
                {busy === 'translate-title' ? <Spinner className="h-3.5 w-3.5 text-zinc-400" /> : null}
                Translate title
              </button>
            </div>
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs text-zinc-400">Russian translation — readers see English when this is empty.</span>
              <button onClick={translate} disabled={busy !== null || !body.trim()} className="inline-flex items-center gap-1.5 rounded bg-zinc-100 px-3 py-1 text-xs disabled:opacity-50">
                {busy === 'translate' ? <Spinner className="h-3.5 w-3.5 text-zinc-400" /> : null}
                Translate from English
              </button>
            </div>
            <div data-color-mode="light">
              <MDEditor value={bodyRu} onChange={(v) => setBodyRu(v ?? '')} height={320} />
            </div>
          </>
        )}
      </div>

      <label className="flex items-center gap-2 text-sm text-zinc-700">
        <input type="checkbox" checked={providerVisible} onChange={(e) => setProviderVisible(e.target.checked)} />
        Visible to providers (owners and managers always have access)
      </label>

      {error ? <p className="text-sm text-red-600">{error}</p> : null}

      <div className="flex gap-2">
        <button onClick={save} disabled={busy !== null} className="inline-flex items-center gap-2 rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40">
          {busy === 'save' ? <Spinner className="h-4 w-4 text-white" /> : null}
          Save
        </button>
        <button onClick={onCancel} disabled={busy !== null} className="rounded-lg px-4 py-2 text-sm ring-1 ring-zinc-200">Cancel</button>
      </div>
    </div>
  );
}
