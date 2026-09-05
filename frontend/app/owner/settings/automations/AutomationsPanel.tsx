'use client';

import { useState } from 'react';
import { api } from '../../../lib/api';
import PromoTermsEditor from './PromoTermsEditor';
import ServiceRolePicker from './ServiceRolePicker';
import type { PromoTermsDto, ServiceLifecycleRoleDto, SmsAutomationSummary } from '../../../lib/types';

// Automation keys whose settings need more than an on/off toggle — each maps to the specific
// ServiceLifecycleRole roles that automation's own eligibility depends on (see
// TouchupReminderScheduler's/ColorBoosterReminderScheduler's own docs: inert until both are
// configured for this business). Adding a future lifecycle-reminder automation means adding one
// entry here, not building a new settings surface. INITIAL_PROCEDURE deliberately appears under
// both — the same qualifying procedure anchors both reminders (see sharedRoleNames below, which
// surfaces that sharing in the UI the same way coupon sharing already is).
const AUTOMATION_SERVICE_ROLES: Record<string, { role: string; label: string }[]> = {
  touchup_reminder: [
    { role: 'INITIAL_PROCEDURE', label: 'Initial procedure' },
    { role: 'TOUCH_UP', label: 'Touch-up' },
  ],
  color_booster_reminder: [
    { role: 'INITIAL_PROCEDURE', label: 'Initial procedure' },
    { role: 'COLOR_BOOSTER', label: 'Color booster' },
  ],
};

// Which vertical each automation is actually about — purely a display grouping/badge (doesn't
// affect eligibility or config), added per direct request now that PMU automations sit alongside
// the original nail-salon ones. "general" covers every automation whose mechanism was already
// business-agnostic before PMU existed (checkout review, lead follow-up, both winbacks, same-day
// rebooking) — none of them reference nails or PMU specifically in their own logic.
type AutomationCategory = 'nails' | 'pmu' | 'general';
const AUTOMATION_CATEGORY: Record<string, AutomationCategory> = {
  four_hand_request: 'nails',
  consultation_lead_sms: 'pmu',
  touchup_reminder: 'pmu',
  color_booster_reminder: 'pmu',
};
const CATEGORY_META: Record<AutomationCategory, { label: string; className: string }> = {
  nails: { label: 'Nails', className: 'bg-rose-50 text-rose-700' },
  pmu: { label: 'PMU', className: 'bg-violet-50 text-violet-700' },
  general: { label: 'General', className: 'bg-zinc-100 text-zinc-600' },
};

// Which channel(s) each automation actually sends over — a display badge per channel so it's
// obvious at a glance without opening the SMS/Email/Telegram tabs. Every automation defaults to
// ['sms'] (the vast majority) — override only for the ones that add a channel:
// lapsed/repeat_customer_winback and same_day_rebooking_discount add an email fallback (see
// WinbackEmailFallbackScheduler: SMS goes out first, email only follows up on customers who
// neither clicked nor replied by evening); four_hand_request also pings staff on Telegram (see
// InternalNotificationController / FourHandRequestNotification) — a different audience (staff,
// not the customer) than its SMS leg (the customer's own confirmation text), but still a real
// second channel worth flagging.
type Channel = 'sms' | 'email' | 'telegram';
const AUTOMATION_CHANNELS: Record<string, Channel[]> = {
  lapsed_customer_winback: ['sms', 'email'],
  repeat_customer_winback: ['sms', 'email'],
  same_day_rebooking_discount: ['sms', 'email'],
  four_hand_request: ['sms', 'telegram'],
};
const CHANNEL_META: Record<Channel, { label: string; dotClassName: string }> = {
  sms: { label: 'SMS', dotClassName: 'bg-sky-500' },
  email: { label: 'Email', dotClassName: 'bg-violet-500' },
  telegram: { label: 'Telegram', dotClassName: 'bg-cyan-500' },
};

// Which coupon(s) (PromoConfigService promoCode) each automation's SMS link actually applies —
// see PromoSettingsController. WINBACK5 deliberately maps to two automations: repeat_customer_
// winback reuses lapsed_customer_winback's own coupon rather than standing up a separate one
// (Square pricing rules are amount-specific, so there's nothing to gain from a second $5/$99 rule)
// — shown under both cards since editing it from either one updates the same underlying discount.
const AUTOMATION_PROMO_CODES: Record<string, string[]> = {
  same_day_rebooking_discount: ['REBOOK10'],
  lapsed_customer_winback: ['WINBACK5'],
  repeat_customer_winback: ['WINBACK5'],
};

// Toggle grid for owner-controlled SMS automations — extracted from the former standalone
// /owner/automations hub, now folded into this page so every SMS-related control (automations,
// activity, credentials) lives on one page (see openspec/changes consolidation request).
export default function AutomationsPanel({
  initialAutomations,
  initialServiceLifecycleRoles,
  initialPromoTerms,
}: {
  initialAutomations: SmsAutomationSummary[];
  initialServiceLifecycleRoles: ServiceLifecycleRoleDto[];
  initialPromoTerms: PromoTermsDto[];
}) {
  const [automations, setAutomations] = useState(initialAutomations);
  const [serviceLifecycleRoles, setServiceLifecycleRoles] = useState(initialServiceLifecycleRoles);
  const [promoTerms, setPromoTerms] = useState(initialPromoTerms);

  async function toggle(key: string, enabled: boolean) {
    // Optimistic — the toggle is the whole interaction, a spinner-then-flip would feel laggy for
    // something this small. Reverted on failure. The toggle is already disabled client-side when
    // turning ON would fail readiness (see AutomationCard), so a failure here is either a stale
    // client (config changed elsewhere in another tab) or turning ON — the backend is the final
    // word either way (see SmsAutomationService#setEnabled), so the reason is always shown.
    setAutomations((prev) => prev.map((a) => (a.key === key ? { ...a, enabled } : a)));
    try {
      await api.toggleSmsAutomation(key, enabled);
    } catch (err) {
      setAutomations((prev) => prev.map((a) => (a.key === key ? { ...a, enabled: !enabled } : a)));
      alert(err instanceof Error ? err.message : 'Failed to update automation');
    }
  }

  function updatePromoTerms(updated: PromoTermsDto) {
    setPromoTerms((prev) => prev.map((t) => (t.promoCode === updated.promoCode ? updated : t)));
  }

  // Enabled automations surface first — what's actually live day-to-day is what an owner checking
  // in on this page wants to see without scrolling past a wall of off switches first. Off ones are
  // grouped in their own section below, not just sorted down inline, so it reads as "here's what's
  // running" vs. "here's what's available" rather than one long mixed list. Stable partition (not
  // a comparator sort) keeps each group in the registry's own order rather than reshuffling by key.
  const enabledAutomations = automations.filter((a) => a.enabled);
  const disabledAutomations = automations.filter((a) => !a.enabled);

  function renderCard(a: SmsAutomationSummary) {
    return (
      <AutomationCard
        key={a.key}
        automation={a}
        category={AUTOMATION_CATEGORY[a.key] ?? 'general'}
        channels={AUTOMATION_CHANNELS[a.key] ?? ['sms']}
        onToggle={(enabled) => toggle(a.key, enabled)}
        serviceRoles={AUTOMATION_SERVICE_ROLES[a.key]}
        serviceLifecycleRoles={serviceLifecycleRoles}
        onServiceLifecycleRolesChange={setServiceLifecycleRoles}
        promoCodes={AUTOMATION_PROMO_CODES[a.key]}
        promoTerms={promoTerms}
        onPromoTermsSaved={updatePromoTerms}
        sharedWith={AUTOMATION_PROMO_CODES[a.key]?.flatMap((code) =>
          Object.entries(AUTOMATION_PROMO_CODES)
            .filter(([otherKey, codes]) => otherKey !== a.key && codes.includes(code))
            .map(([otherKey]) => automations.find((x) => x.key === otherKey)?.name ?? otherKey),
        )}
        sharedRoleNames={(role) =>
          Object.entries(AUTOMATION_SERVICE_ROLES)
            .filter(([otherKey, roles]) => otherKey !== a.key && roles.some((r) => r.role === role))
            .map(([otherKey]) => automations.find((x) => x.key === otherKey)?.name ?? otherKey)
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-5">
      {enabledAutomations.length > 0 && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {enabledAutomations.map(renderCard)}
        </div>
      )}
      {disabledAutomations.length > 0 && (
        <div>
          {enabledAutomations.length > 0 && (
            <div className="mb-3 flex items-center gap-2">
              <span className="text-xs font-semibold uppercase tracking-wide text-zinc-400">Off</span>
              <div className="h-px flex-1 bg-zinc-100" />
            </div>
          )}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {disabledAutomations.map(renderCard)}
          </div>
        </div>
      )}
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
  category,
  channels,
  onToggle,
  serviceRoles,
  serviceLifecycleRoles,
  onServiceLifecycleRolesChange,
  promoCodes,
  promoTerms,
  onPromoTermsSaved,
  sharedWith,
  sharedRoleNames,
}: {
  automation: SmsAutomationSummary;
  category: AutomationCategory;
  channels: Channel[];
  onToggle: (enabled: boolean) => void;
  serviceRoles?: { role: string; label: string }[];
  serviceLifecycleRoles: ServiceLifecycleRoleDto[];
  onServiceLifecycleRolesChange: (next: ServiceLifecycleRoleDto[]) => void;
  promoCodes?: string[];
  promoTerms: PromoTermsDto[];
  onPromoTermsSaved: (t: PromoTermsDto) => void;
  sharedWith?: string[];
  sharedRoleNames?: (role: string) => string[];
}) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const clickRate = automation.tracksClicks ? formatRate(automation.clickedLast30Days, automation.linkSentLast30Days) : undefined;
  const replyRate = automation.tracksReplies ? formatRate(automation.replyLast30Days, automation.sentLast30Days) : undefined;
  const conversionRate = automation.tracksConversion
    ? formatRate(automation.convertedLast30Days, automation.sentLast30Days)
    : undefined;
  const emailOpenRate = automation.tracksEmail
    ? formatRate(automation.emailOpenedLast30Days, automation.emailSentLast30Days)
    : undefined;
  const emailClickRate = automation.tracksEmail
    ? formatRate(automation.emailClickedLast30Days, automation.emailSentLast30Days)
    : undefined;
  const emailConversionRate = automation.tracksEmail
    ? formatRate(automation.emailConvertedLast30Days, automation.emailSentLast30Days)
    : undefined;

  const hasSettings = !!(serviceRoles || promoCodes);
  const configuredRoleCount = serviceRoles?.filter((r) => serviceLifecycleRoles.some((x) => x.role === r.role)).length ?? 0;
  const roleTotal = serviceRoles?.length ?? 0;
  const configuredPromoCount = promoCodes?.filter((code) => promoTerms.find((t) => t.promoCode === code)?.configured).length ?? 0;
  const promoTotal = promoCodes?.length ?? 0;
  const configuredCount = configuredRoleCount + configuredPromoCount;
  const totalCount = roleTotal + promoTotal;
  const fullyConfigured = totalCount === 0 || configuredCount === totalCount;
  // Turning ON is blocked when required config is missing — never blocks turning OFF, and never
  // affects an automation that's already on (see AutomationReadinessService's own doc: config
  // removed after enabling doesn't retroactively disable it here).
  const blockedFromEnabling = !automation.enabled && !automation.ready;

  return (
    <div className="flex flex-col gap-3 rounded-lg p-4 ring-1 ring-zinc-200">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-1.5">
            <span className="font-medium text-zinc-900">{automation.name}</span>
            <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${CATEGORY_META[category].className}`}>
              {CATEGORY_META[category].label}
            </span>
            {channels.map((ch) => (
              <span
                key={ch}
                className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-600"
                title={
                  ch === 'email' ? 'Email fallback for customers who don’t click/reply to the SMS by evening'
                    : ch === 'telegram' ? 'Pings staff on Telegram'
                    : 'Sends SMS'
                }
              >
                <span className={`h-1.5 w-1.5 rounded-full ${CHANNEL_META[ch].dotClassName}`} aria-hidden />
                {CHANNEL_META[ch].label}
              </span>
            ))}
          </div>
          <div className="mt-0.5 text-xs text-zinc-500">{automation.audienceDescription}</div>
        </div>
        {/* Full-size touch target, not shrunk to fit next to the label — see design.md D10. */}
        <button
          type="button"
          role="switch"
          aria-checked={automation.enabled}
          aria-label={`${automation.enabled ? 'Disable' : 'Enable'} ${automation.name}`}
          disabled={blockedFromEnabling}
          title={blockedFromEnabling ? automation.readinessReason ?? undefined : undefined}
          onClick={() => onToggle(!automation.enabled)}
          className={`relative inline-flex h-8 w-14 shrink-0 items-center rounded-full transition-colors ${
            automation.enabled ? 'bg-emerald-600' : 'bg-zinc-300'
          } ${blockedFromEnabling ? 'cursor-not-allowed opacity-50' : ''}`}
        >
          <span
            className={`inline-block h-6 w-6 transform rounded-full bg-white shadow transition-transform ${
              automation.enabled ? 'translate-x-7' : 'translate-x-1'
            }`}
          />
        </button>
      </div>
      {blockedFromEnabling && (
        <p className="-mt-1.5 text-xs text-amber-600">⚠ {automation.readinessReason}</p>
      )}
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
      {/* Email fallback stats — a separate channel from the SMS row above (see WinbackEmailFallback
          Scheduler), so kept on its own row with a small envelope marker rather than mixed into the
          SMS pills, which would blur which channel each number is actually about. Omitted the same
          way as the SMS pills when the automation doesn't track email or hasn't sent one yet. */}
      {(emailOpenRate || emailClickRate || emailConversionRate) && (
        <div className="flex flex-wrap items-center gap-1.5">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0 text-zinc-400">
            <rect x="3" y="5" width="18" height="14" rx="2" />
            <path d="m3 6 9 6 9-6" />
          </svg>
          {emailOpenRate && (
            <span
              data-testid="automation-email-open-rate"
              className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-1 text-xs font-medium text-amber-700"
            >
              {emailOpenRate} opened
              <span className="font-normal text-amber-600/70 tabular-nums">
                · {automation.emailOpenedLast30Days}/{automation.emailSentLast30Days}
              </span>
            </span>
          )}
          {emailClickRate && (
            <span
              data-testid="automation-email-click-rate"
              className="inline-flex items-center gap-1 rounded-full bg-indigo-50 px-2 py-1 text-xs font-medium text-indigo-700"
            >
              {emailClickRate} clicked
              <span className="font-normal text-indigo-600/70 tabular-nums">
                · {automation.emailClickedLast30Days}/{automation.emailSentLast30Days}
              </span>
            </span>
          )}
          {emailConversionRate && (
            <span
              data-testid="automation-email-conversion-rate"
              className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-1 text-xs font-medium text-emerald-700"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
                <path d="M20 6 9 17l-5-5" />
              </svg>
              {emailConversionRate} {automation.key === 'checkout_review_request' ? 'rated' : 'returned'}
              <span className="font-normal text-emerald-600/70 tabular-nums">
                · {automation.emailConvertedLast30Days}/{automation.emailSentLast30Days}
              </span>
            </span>
          )}
        </div>
      )}

      {hasSettings && (
        <div className="-mx-1 border-t border-zinc-100 pt-2.5">
          <button
            type="button"
            onClick={() => setSettingsOpen((v) => !v)}
            className="mx-1 flex w-[calc(100%-8px)] items-center justify-between text-xs font-medium text-zinc-600 hover:text-zinc-900"
          >
            <span className="inline-flex items-center gap-1.5">
              <span className={`transition-transform ${settingsOpen ? 'rotate-90' : ''}`}>›</span>
              Settings
            </span>
            {!fullyConfigured && (
              <span className="rounded-full bg-amber-50 px-2 py-0.5 font-medium text-amber-700">
                {configuredCount}/{totalCount} set up
              </span>
            )}
          </button>
          {settingsOpen && (
            <div className="mx-1 mt-2.5 flex flex-col gap-3">
              {serviceRoles?.map((r) => {
                const sharedRoleWith = sharedRoleNames?.(r.role) ?? [];
                return (
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
                    {sharedRoleWith.length > 0 && (
                      <p className="mt-1.5 text-xs text-zinc-400">
                        Also used by {sharedRoleWith.join(', ')} — editing it here updates it there too.
                      </p>
                    )}
                  </div>
                );
              })}
              {promoCodes?.map((code) => {
                const terms = promoTerms.find((t) => t.promoCode === code);
                if (!terms) return null;
                return (
                  <div key={code}>
                    <PromoTermsEditor terms={terms} onSaved={onPromoTermsSaved} />
                    {sharedWith && sharedWith.length > 0 && (
                      <p className="mt-1.5 text-xs text-zinc-400">
                        Same coupon as {sharedWith.join(', ')} — editing it here updates it there too.
                      </p>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
