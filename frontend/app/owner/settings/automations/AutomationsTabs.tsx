'use client';

import { useSearchParams } from 'next/navigation';
import { useState } from 'react';
import AutomationsPanel from './AutomationsPanel';
import SmsActivityLog from './SmsActivityLog';
import TemplatesPanel from './TemplatesPanel';
import TwilioSmsSettingsForm from './TwilioSmsSettingsForm';
import MailchimpSettingsForm from './MailchimpSettingsForm';
import MailchimpActivityLog from './MailchimpActivityLog';
import TelegramSettingsForm from './TelegramSettingsForm';
import type {
  MailchimpActivityResponse,
  MailchimpSettingsDto,
  PromoTermsDto,
  ServiceLifecycleRoleDto,
  SmsAutomationSummary,
  SmsTemplateView,
  TelegramSettingsDto,
  TwilioSmsSettingsDto,
} from '../../../lib/types';

type TabKey = 'overview' | 'sms' | 'email' | 'telegram';

const TABS: { key: TabKey; label: string; dot: string }[] = [
  { key: 'overview', label: 'Overview', dot: 'bg-zinc-400' },
  { key: 'sms', label: 'SMS', dot: 'bg-sky-500' },
  { key: 'email', label: 'Email', dot: 'bg-violet-500' },
  { key: 'telegram', label: 'Telegram', dot: 'bg-cyan-500' },
];

function isTabKey(v: string | null): v is TabKey {
  return v === 'overview' || v === 'sms' || v === 'email' || v === 'telegram';
}

/** Client-side tabs so switching between Overview/SMS/Email/Telegram is instant (no navigation, no
 * re-fetch) — all the data these tabs render is already fetched once, server-side, by page.tsx.
 * Splitting by channel exists so an owner who only cares about, say, email deliverability can go
 * straight there without scrolling past the SMS template editor (which can run long — see
 * TemplatesPanel).
 *
 * <p>Initial tab reads once from {@code ?tab=} (not kept in sync afterward — clicking a tab is
 * plain local state, no router.push) purely so a deep link — e.g. the old
 * {@code /owner/settings/telegram} redirect — can land on the right tab instead of always
 * Overview. Not synced continuously so switching tabs never re-triggers this page's server-side
 * data fetch (Promise.all in page.tsx). */
export default function AutomationsTabs({
  automations,
  serviceLifecycleRoles,
  promoTerms,
  templates,
  twilioSettings,
  mailchimpSettings,
  mailchimpActivity,
  telegramSettings,
}: {
  automations: SmsAutomationSummary[];
  serviceLifecycleRoles: ServiceLifecycleRoleDto[];
  promoTerms: PromoTermsDto[];
  templates: SmsTemplateView[];
  twilioSettings: TwilioSmsSettingsDto;
  mailchimpSettings: MailchimpSettingsDto;
  mailchimpActivity: MailchimpActivityResponse;
  telegramSettings: TelegramSettingsDto;
}) {
  const searchParams = useSearchParams();
  const initialTab = searchParams.get('tab');
  const [tab, setTab] = useState<TabKey>(isTabKey(initialTab) ? initialTab : 'overview');

  return (
    <div>
      <div role="tablist" aria-label="Automations sections" className="flex gap-1 border-b border-zinc-200">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={`flex items-center gap-1.5 border-b-2 px-3 py-2.5 text-sm font-medium transition-colors ${
              tab === t.key
                ? 'border-zinc-900 text-zinc-900'
                : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${t.dot}`} aria-hidden />
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && (
        <section className="mt-6">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Automations</h2>
          <p className="mt-1 text-sm text-zinc-500">
            Automated messages we send, across every channel. New automations always ship off —
            turn one on once you&apos;ve tested it. The channel dots next to each one show whether
            it sends SMS, email (SMS first, email only as a fallback for non-responders — see the
            Email tab), and/or a Telegram alert to staff.
          </p>
          <div className="mt-4">
            <AutomationsPanel
              initialAutomations={automations}
              initialServiceLifecycleRoles={serviceLifecycleRoles}
              initialPromoTerms={promoTerms}
            />
          </div>
        </section>
      )}

      {tab === 'sms' && (
        <>
          <section className="mt-6">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Twilio credentials</h2>
            <p className="mt-1 text-sm text-zinc-500">
              Sends SMS to customers (e.g. confirming a 4-hand request). Leave any field blank to
              turn SMS off — nothing else breaks if credentials are unset.
            </p>
            <TwilioSmsSettingsForm initialSettings={twilioSettings} />
          </section>

          <section className="mt-8">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Message wording</h2>
            <p className="mt-1 text-sm text-zinc-500">
              Edit the exact text each automation sends. {'{{variables}}'} fill in automatically —
              don&apos;t remove or rename them.
            </p>
            <TemplatesPanel initialTemplates={templates} automations={automations} />
          </section>

          <section className="mt-8">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Activity</h2>
            <p className="mt-1 text-sm text-zinc-500">
              Every text we&apos;ve sent and every reply we&apos;ve received, regardless of
              automation. To reply to a customer, use{' '}
              <a href="/admin/messages" className="font-medium text-sky-700 underline">
                Messages
              </a>{' '}
              instead — this log is read-only.
            </p>
            <SmsActivityLog />
          </section>
        </>
      )}

      {tab === 'email' && (
        <>
          <section className="mt-6">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Mailchimp credentials</h2>
            <p className="mt-1 text-sm text-zinc-500">
              Sends marketing win-back emails (lapsed &amp; repeat customer) alongside the matching
              SMS automations. Leave any field blank to keep win-back emails off — nothing else
              breaks if credentials are unset.
            </p>
            <MailchimpSettingsForm initialSettings={mailchimpSettings} />
          </section>

          <section className="mt-8">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Email activity</h2>
            <p className="mt-1 text-sm text-zinc-500">
              The win-back email fallback: sends only to customers who neither clicked their SMS
              link nor replied by evening. Open/click numbers sync from Mailchimp every 30 minutes,
              not live.
            </p>
            <MailchimpActivityLog data={mailchimpActivity} />
          </section>
        </>
      )}

      {tab === 'telegram' && (
        <section className="mt-6">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-zinc-500">Telegram credentials</h2>
          <p className="mt-1 text-sm text-zinc-500">
            Alerts staff on Telegram when a 4-hand request comes in from either booking site. Leave
            either field blank to turn alerts off — nothing else breaks if they&apos;re unset.
          </p>
          <TelegramSettingsForm initialSettings={telegramSettings} />
        </section>
      )}
    </div>
  );
}
