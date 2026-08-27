'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import { Spinner } from '../../../components/Spinner';
import type { SmsAutomationSummary, SmsTemplateVariantView, SmsTemplateView } from '../../../lib/types';

// Owner-editable wording for every automated SMS, grouped under the automation card it belongs
// to — a template with no matching automation (shouldn't happen today, but the catalog doesn't
// guarantee it) falls into its own "Other" group rather than being silently dropped.
export default function TemplatesPanel({
  initialTemplates,
  automations,
}: {
  initialTemplates: SmsTemplateView[];
  automations: SmsAutomationSummary[];
}) {
  const [templates, setTemplates] = useState(initialTemplates);
  const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());
  // Collapsed by default, same as groups — a rotating template alone can be 5 textareas, and a
  // group can hold several templates, so leaving every one of them open the instant its group
  // expands is what actually made this page feel like "so many messages" (owner feedback). All
  // the data is already loaded (one request for the whole page); this only toggles what's
  // rendered, not a fresh fetch per template.
  const [openTemplates, setOpenTemplates] = useState<Set<string>>(new Set());

  function toggleGroup(key: string) {
    setOpenGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function toggleTemplate(key: string) {
    setOpenTemplates((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function applyVariantUpdate(templateKey: string, updated: SmsTemplateVariantView) {
    setTemplates((prev) =>
      prev.map((t) =>
        t.key !== templateKey
          ? t
          : { ...t, variants: t.variants.map((v) => (v.index === updated.index ? updated : v)) },
      ),
    );
  }

  const nameByAutomation = new Map(automations.map((a) => [a.key, a.name]));
  const groupOrder = automations.map((a) => a.key);
  const grouped = new Map<string, SmsTemplateView[]>();
  for (const t of templates) {
    const groupKey = t.automationKey ?? '__other__';
    const list = grouped.get(groupKey) ?? [];
    list.push(t);
    grouped.set(groupKey, list);
  }
  const orderedGroupKeys = [...groupOrder.filter((k) => grouped.has(k)), ...(grouped.has('__other__') ? ['__other__'] : [])];

  return (
    <div className="mt-4 flex flex-col gap-3">
      {orderedGroupKeys.map((groupKey) => {
        const groupTemplates = grouped.get(groupKey) ?? [];
        const open = openGroups.has(groupKey);
        const label = nameByAutomation.get(groupKey) ?? 'Other';
        const anyCustomized = groupTemplates.some((t) => t.variants.some((v) => v.customized));
        return (
          <div key={groupKey} className="rounded-lg ring-1 ring-zinc-200">
            <button
              type="button"
              onClick={() => toggleGroup(groupKey)}
              aria-expanded={open}
              className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
            >
              <span className="flex items-center gap-2">
                <span className="font-medium text-zinc-900">{label}</span>
                {anyCustomized && (
                  <span className="rounded-full bg-sky-50 px-2 py-0.5 text-xs font-medium text-sky-700">Customized</span>
                )}
              </span>
              <span className="flex items-center gap-2 text-xs text-zinc-400">
                {groupTemplates.length} message{groupTemplates.length === 1 ? '' : 's'}
                <Chevron open={open} />
              </span>
            </button>
            {open && (
              <div className="flex flex-col gap-2 border-t border-zinc-100 px-4 py-3">
                {groupTemplates.map((t) => (
                  <TemplateEditor
                    key={t.key}
                    template={t}
                    open={openTemplates.has(t.key)}
                    onToggle={() => toggleTemplate(t.key)}
                    onVariantSaved={(v) => applyVariantUpdate(t.key, v)}
                  />
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={`shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}

function TemplateEditor({
  template,
  open,
  onToggle,
  onVariantSaved,
}: {
  template: SmsTemplateView;
  open: boolean;
  onToggle: () => void;
  onVariantSaved: (variant: SmsTemplateVariantView) => void;
}) {
  const rotates = template.variants.length > 1;
  const customized = template.variants.some((v) => v.customized);
  return (
    <div className="rounded-md ring-1 ring-zinc-100">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left"
      >
        <span className="flex items-center gap-2">
          <span className="text-sm font-medium text-zinc-700">{template.label}</span>
          {customized && <span className="text-xs text-zinc-400">Customized</span>}
        </span>
        <span className="flex items-center gap-2 text-xs text-zinc-400">
          {rotates ? `${template.variants.length} variants` : '1 message'}
          <Chevron open={open} />
        </span>
      </button>
      {open && (
        <div className="border-t border-zinc-100 bg-zinc-50 px-3 py-3">
          {rotates && (
            <p className="mb-2 text-xs text-zinc-400">
              Sent in order, one per visit, so a repeat customer never sees the same wording twice in a row — edit
              any one below without affecting the others, or reset it back to rotating on its own default.
            </p>
          )}
          <div className={rotates ? 'flex flex-col gap-2.5' : ''}>
            {template.variants.map((variant) => (
              <VariantEditor
                key={variant.index}
                templateKey={template.key}
                variant={variant}
                variantLabel={rotates ? `Variant ${variant.index + 1} of ${template.variants.length}` : null}
                variables={template.variables}
                onSaved={onVariantSaved}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function VariantEditor({
  templateKey,
  variant,
  variantLabel,
  variables,
  onSaved,
}: {
  templateKey: string;
  variant: SmsTemplateVariantView;
  variantLabel: string | null;
  variables: string[];
  onSaved: (variant: SmsTemplateVariantView) => void;
}) {
  const [value, setValue] = useState(variant.body);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [error, setError] = useState('');

  const dirty = value !== variant.body;

  async function save() {
    setSaving(true);
    setError('');
    try {
      const updated = await api.updateSmsTemplateVariant(templateKey, variant.index, value);
      onSaved(updated);
      setValue(updated.body);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save');
    } finally {
      setSaving(false);
    }
  }

  async function reset() {
    setResetting(true);
    setError('');
    try {
      const updated = await api.resetSmsTemplateVariant(templateKey, variant.index);
      onSaved(updated);
      setValue(updated.body);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reset');
    } finally {
      setResetting(false);
    }
  }

  return (
    <div className="rounded-md bg-white p-3 ring-1 ring-zinc-200">
      {variantLabel && (
        <div className="mb-1.5 flex items-center justify-between gap-2">
          <span className="text-xs font-medium text-zinc-500">{variantLabel}</span>
          {variant.customized && <span className="text-xs text-zinc-400">Customized</span>}
        </div>
      )}
      {!variantLabel && variant.customized && (
        <div className="mb-1.5 flex justify-end">
          <span className="text-xs text-zinc-400">Customized</span>
        </div>
      )}
      <textarea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        rows={3}
        className="w-full rounded border border-zinc-300 bg-white px-2 py-1.5 text-sm"
      />
      {variables.length > 0 && (
        <p className="mt-1 text-xs text-zinc-400">Available: {variables.map((v) => `{{${v}}}`).join(', ')}</p>
      )}
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      <div className="mt-2 flex items-center gap-3">
        <button
          type="button"
          onClick={save}
          disabled={!dirty || saving}
          className="inline-flex items-center gap-1.5 rounded bg-zinc-900 px-3 py-1 text-xs font-medium text-white disabled:opacity-40"
        >
          {saving && <Spinner className="h-3 w-3" />}
          {saving ? 'Saving…' : 'Save'}
        </button>
        {variant.customized && (
          <button
            type="button"
            onClick={reset}
            disabled={resetting}
            className="inline-flex items-center gap-1.5 rounded px-2 py-1 text-xs text-zinc-500 hover:text-zinc-700 disabled:opacity-40"
          >
            {resetting && <Spinner className="h-3 w-3" />}
            {resetting ? 'Resetting…' : 'Reset to default'}
          </button>
        )}
      </div>
    </div>
  );
}
