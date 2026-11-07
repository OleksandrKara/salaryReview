'use client';

import Link from 'next/link';
import { t } from '../../lib/i18n';
import type { Language, Lead } from '../../lib/types';
import { actionPlan } from './leadActions';

export interface LeadActionHandlers {
  onReachedOut: () => void;
  onNoAnswer: () => void;
  onCloseLost: () => void;
  onSpam: () => void;
}

const base = 'inline-flex h-9 min-w-0 items-center justify-center whitespace-nowrap rounded-md px-2 text-center text-xs font-medium leading-none disabled:opacity-40';
const quiet = `${base} ring-1 ring-zinc-300 text-zinc-800 hover:bg-zinc-100`;
const faint = `${base} text-zinc-600 ring-1 ring-zinc-200 hover:bg-zinc-100`;

/** One button set for the list row and the lead card, so a reminder can be handled from either place. */
export default function LeadActions({ lead, now, language, busy, withContact, handlers }: {
  lead: Lead; now: number; language: Language | null; busy: boolean; withContact: boolean; handlers: LeadActionHandlers;
}) {
  const p = actionPlan(lead, now);
  if (!p.primary && !p.reachedSecondary && !p.noAnswer && !p.spam) return null;
  const secondary = Number(p.reachedSecondary) + Number(p.noAnswer) + Number(p.spam);
  const phone = encodeURIComponent(lead.phoneNumber ?? '');
  return (
    <div className="grid w-full grid-cols-2 gap-1.5 sm:max-w-sm lg:w-60 lg:shrink-0" data-testid="lead-actions">
      {withContact && p.call && <a href={`tel:${lead.phoneNumber}`} className={quiet}>{t(language, 'crmCall')}</a>}
      {withContact && p.text && <Link href={`/admin/messages?phone=${phone}`} className={quiet}>{t(language, 'crmText')}</Link>}
      {p.primary === 'CLOSE' && (
        <button className={`${base} col-span-2 bg-red-600 text-white`} disabled={busy} onClick={handlers.onCloseLost}>{t(language, 'crmCloseNoAnswer')}</button>
      )}
      {p.primary === 'REACHED' && (
        <button className={`${base} col-span-2 h-10 bg-emerald-600 text-sm text-white`} disabled={busy} onClick={handlers.onReachedOut}>{t(language, 'crmLapsedDone')}</button>
      )}
      {p.reachedSecondary && (
        <button className={`${quiet} text-emerald-800 ring-emerald-300`} disabled={busy} onClick={handlers.onReachedOut}>{t(language, 'crmLapsedDone')}</button>
      )}
      {p.noAnswer && <button className={quiet} disabled={busy} onClick={handlers.onNoAnswer}>{t(language, 'crmNoAnswer')}</button>}
      {p.spam && <button className={`${faint}${secondary % 2 === 1 ? ' col-span-2' : ''}`} disabled={busy} onClick={handlers.onSpam}>{t(language, 'crmMarkSpam')}</button>}
    </div>
  );
}
