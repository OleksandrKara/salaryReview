'use client';

import VipIcon from '../messages/VipIcon';
import SmsConsentIcon from '../messages/SmsConsentIcon';
import NegativeFeedbackIcon from '../messages/NegativeFeedbackIcon';
import BlockedIcon from '../messages/BlockedIcon';
import SpamFlagIcon from '../messages/SpamFlagIcon';
import GoogleReviewClickedIcon from '../messages/GoogleReviewClickedIcon';
import YelpReviewClickedIcon from '../messages/YelpReviewClickedIcon';
import FeedbackFormClickedIcon from '../messages/FeedbackFormClickedIcon';
import FlagIcon from './FlagIcon';
import { t } from '../../lib/i18n';
import type { Language, LeadListBadge } from '../../lib/types';

const SCOPE_RING: Record<string, string> = {
  LOCAL: 'ring-emerald-300',
  OUT_OF_STATE: 'ring-amber-400',
  INTERNATIONAL: 'ring-violet-400',
};

/** Icon-only strip for list rows: warnings first, then status, then engagement; every icon keeps its tooltip. */
export default function LeadIcons({ b, language }: { b: LeadListBadge | undefined; language: Language | null }) {
  if (!b) return null;
  const size = 14;
  return (
    <span className="inline-flex shrink-0 items-center gap-1 align-middle" data-testid="lead-icons">
      {b.blocked && <BlockedIcon optedOut={b.optedOut} size={size} />}
      {b.negativeFeedback && <NegativeFeedbackIcon size={size} />}
      {b.spamFlag && <SpamFlagIcon size={size} />}
      {b.vip && <VipIcon visitCount={b.visitCount} size={size} />}
      {b.smsConsent && <SmsConsentIcon size={size} />}
      {b.clickedGoogle && <GoogleReviewClickedIcon size={size} />}
      {b.clickedYelp && <YelpReviewClickedIcon size={size} />}
      {b.clickedFeedback && <FeedbackFormClickedIcon size={size} />}
      {b.flag && (
        <span
          className={`inline-flex h-[18px] w-[18px] items-center justify-center rounded-full bg-white text-[11px] leading-none ring-1 ${(b.scope ? SCOPE_RING[b.scope] : 'ring-zinc-300')}`}
          title={b.scope ? `${b.place ?? ''} · ${t(language, b.scope === 'LOCAL' ? 'crmPlaceLocal' : b.scope === 'OUT_OF_STATE' ? 'crmPlaceOut' : 'crmPlaceIntl')}` : (b.place ?? '')}
        >
          <FlagIcon flag={b.flag} className="h-[14px] w-[14px] rounded-full object-cover" />
          <span className="sr-only">{b.place}</span>
        </span>
      )}
    </span>
  );
}
