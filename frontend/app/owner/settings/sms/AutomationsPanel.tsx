'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import type { SmsAutomationSummary } from '../../../lib/types';

// Toggle grid for owner-controlled SMS automations — extracted from the former standalone
// /owner/automations hub, now folded into this page so every SMS-related control (automations,
// activity, credentials) lives on one page (see openspec/changes consolidation request).
export default function AutomationsPanel({ initialAutomations }: { initialAutomations: SmsAutomationSummary[] }) {
  const [automations, setAutomations] = useState(initialAutomations);

  async function toggle(key: string, enabled: boolean) {
    // Optimistic — the toggle is the whole interaction, a spinner-then-flip would feel laggy for
    // something this small. Reverted on failure.
    setAutomations((prev) => prev.map((a) => (a.key === key ? { ...a, enabled } : a)));
    try {
      await api.toggleSmsAutomation(key, enabled);
    } catch {
      setAutomations((prev) => prev.map((a) => (a.key === key ? { ...a, enabled: !enabled } : a)));
    }
  }

  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {automations.map((a) => (
        <AutomationCard key={a.key} automation={a} onToggle={(enabled) => toggle(a.key, enabled)} />
      ))}
    </div>
  );
}

function AutomationCard({
  automation,
  onToggle,
}: {
  automation: SmsAutomationSummary;
  onToggle: (enabled: boolean) => void;
}) {
  return (
    <div className="flex flex-col gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="font-medium text-zinc-900">{automation.name}</div>
          <div className="mt-0.5 text-xs text-zinc-500">{automation.audienceDescription}</div>
        </div>
        {/* Full-size touch target, not shrunk to fit next to the label — see design.md D10. */}
        <button
          type="button"
          role="switch"
          aria-checked={automation.enabled}
          aria-label={`${automation.enabled ? 'Disable' : 'Enable'} ${automation.name}`}
          onClick={() => onToggle(!automation.enabled)}
          className={`relative inline-flex h-8 w-14 shrink-0 items-center rounded-full transition-colors ${
            automation.enabled ? 'bg-emerald-600' : 'bg-zinc-300'
          }`}
        >
          <span
            className={`inline-block h-6 w-6 transform rounded-full bg-white shadow transition-transform ${
              automation.enabled ? 'translate-x-7' : 'translate-x-1'
            }`}
          />
        </button>
      </div>
      <div className="flex items-center justify-between text-xs text-zinc-500">
        <span
          className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium ${
            automation.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-zinc-100 text-zinc-500'
          }`}
        >
          <span className={`h-1.5 w-1.5 rounded-full ${automation.enabled ? 'bg-emerald-600' : 'bg-zinc-400'}`} />
          {automation.enabled ? 'On' : 'Off'}
        </span>
        <span>{automation.sentLast30Days} sent (30d)</span>
      </div>
    </div>
  );
}
