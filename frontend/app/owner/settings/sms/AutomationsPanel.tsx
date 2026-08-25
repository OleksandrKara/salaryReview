'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import ServiceRolePicker from './ServiceRolePicker';
import type { ServiceLifecycleRoleDto, SmsAutomationSummary } from '../../../lib/types';

// Automation keys whose settings need more than an on/off toggle — each maps to the specific
// ServiceLifecycleRole roles that automation's own eligibility depends on (see
// TouchupReminderScheduler's own doc: inert until both are configured for this business). Adding a
// future lifecycle-reminder automation (e.g. an eventual color-booster reminder) means adding one
// entry here, not building a new settings surface.
const AUTOMATION_SERVICE_ROLES: Record<string, { role: string; label: string }[]> = {
  touchup_reminder: [
    { role: 'INITIAL_PROCEDURE', label: 'Initial procedure' },
    { role: 'TOUCH_UP', label: 'Touch-up' },
  ],
};

// Toggle grid for owner-controlled SMS automations — extracted from the former standalone
// /owner/automations hub, now folded into this page so every SMS-related control (automations,
// activity, credentials) lives on one page (see openspec/changes consolidation request).
export default function AutomationsPanel({
  initialAutomations,
  initialServiceLifecycleRoles,
}: {
  initialAutomations: SmsAutomationSummary[];
  initialServiceLifecycleRoles: ServiceLifecycleRoleDto[];
}) {
  const [automations, setAutomations] = useState(initialAutomations);
  const [serviceLifecycleRoles, setServiceLifecycleRoles] = useState(initialServiceLifecycleRoles);

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
        <AutomationCard
          key={a.key}
          automation={a}
          onToggle={(enabled) => toggle(a.key, enabled)}
          serviceRoles={AUTOMATION_SERVICE_ROLES[a.key]}
          serviceLifecycleRoles={serviceLifecycleRoles}
          onServiceLifecycleRolesChange={setServiceLifecycleRoles}
        />
      ))}
    </div>
  );
}

// undefined (not 0%) when there's nothing to divide yet — an automation that hasn't fired in the
// last 30 days shouldn't read as "0% clicked", it should just not show a rate at all.
function formatRate(numerator: number, denominator: number): string | undefined {
  if (denominator <= 0) return undefined;
  return `${Math.round((numerator / denominator) * 100)}%`;
}

function AutomationCard({
  automation,
  onToggle,
  serviceRoles,
  serviceLifecycleRoles,
  onServiceLifecycleRolesChange,
}: {
  automation: SmsAutomationSummary;
  onToggle: (enabled: boolean) => void;
  serviceRoles?: { role: string; label: string }[];
  serviceLifecycleRoles: ServiceLifecycleRoleDto[];
  onServiceLifecycleRolesChange: (next: ServiceLifecycleRoleDto[]) => void;
}) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const clickRate = automation.tracksClicks ? formatRate(automation.clickedLast30Days, automation.linkSentLast30Days) : undefined;
  const replyRate = automation.tracksReplies ? formatRate(automation.replyLast30Days, automation.sentLast30Days) : undefined;
  const conversionRate = automation.tracksConversion
    ? formatRate(automation.convertedLast30Days, automation.sentLast30Days)
    : undefined;

  const configuredCount = serviceRoles?.filter((r) => serviceLifecycleRoles.some((x) => x.role === r.role)).length ?? 0;
  const fullyConfigured = serviceRoles ? configuredCount === serviceRoles.length : true;

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
      {/* Click/reply/conversion rate — omitted entirely for an automation with no trackable link,
          reply-ask, or measurable outcome (see formatRate), and for one that's tracked but simply
          hasn't fired yet. Counts are spelled out inline (not just a percentage, not just a hover
          tooltip) so the numbers a manager actually asked for — "количество кликов и ответов", and
          whether the customer actually came back — are visible without a mouse, on a phone screen
          just as well as a desktop one. */}
      {(clickRate || replyRate || conversionRate) && (
        <div className="flex flex-wrap gap-1.5">
          {clickRate && (
            <span
              data-testid="automation-click-rate"
              className="inline-flex items-center gap-1 rounded-full bg-sky-50 px-2 py-1 text-xs font-medium text-sky-700"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
                <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
              </svg>
              {clickRate} clicked
              <span className="font-normal text-sky-600/70 tabular-nums">
                · {automation.clickedLast30Days}/{automation.linkSentLast30Days}
              </span>
            </span>
          )}
          {replyRate && (
            <span
              data-testid="automation-reply-rate"
              className="inline-flex items-center gap-1 rounded-full bg-violet-50 px-2 py-1 text-xs font-medium text-violet-700"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
                <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
              </svg>
              {replyRate} replied
              <span className="font-normal text-violet-600/70 tabular-nums">
                · {automation.replyLast30Days}/{automation.sentLast30Days}
              </span>
            </span>
          )}
          {conversionRate && (
            <span
              data-testid="automation-conversion-rate"
              className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-1 text-xs font-medium text-emerald-700"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
                <path d="M20 6 9 17l-5-5" />
              </svg>
              {conversionRate} returned
              <span className="font-normal text-emerald-600/70 tabular-nums">
                · {automation.convertedLast30Days}/{automation.sentLast30Days}
              </span>
            </span>
          )}
        </div>
      )}

      {serviceRoles && (
        <div className="-mx-1 border-t border-zinc-100 pt-2.5">
          <button
            type="button"
            onClick={() => setSettingsOpen((v) => !v)}
            className="mx-1 flex w-[calc(100%-8px)] items-center justify-between text-xs font-medium text-zinc-600 hover:text-zinc-900"
          >
            <span className="inline-flex items-center gap-1.5">
              <span className={`transition-transform ${settingsOpen ? 'rotate-90' : ''}`}>›</span>
              Configure services
            </span>
            {!fullyConfigured && (
              <span className="rounded-full bg-amber-50 px-2 py-0.5 font-medium text-amber-700">
                {configuredCount}/{serviceRoles.length} set up
              </span>
            )}
          </button>
          {settingsOpen && (
            <div className="mx-1 mt-2.5 flex flex-col gap-3">
              {serviceRoles.map((r) => (
                <div key={r.role}>
                  <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-zinc-500">{r.label}</div>
                  <ServiceRolePicker
                    role={r.role}
                    entries={serviceLifecycleRoles.filter((x) => x.role === r.role)}
                    onChange={(nextForRole) =>
                      onServiceLifecycleRolesChange([
                        ...serviceLifecycleRoles.filter((x) => x.role !== r.role),
                        ...nextForRole,
                      ])
                    }
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
