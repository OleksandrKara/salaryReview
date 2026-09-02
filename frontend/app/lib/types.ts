// Mirrors the DTOs returned by the Spring backend in com.salonreview.web.dto.*

export type Half = 'FIRST' | 'SECOND';

// --- RAG knowledge assistant (com.salonreview.rag / web.Rag*Controller) ---

export interface RagCitation {
  documentId: number | null;
  documentTitle: string;
  citedText: string;
}

export interface RagAnswer {
  answer: string;
  citations: RagCitation[];
  configVersion: number;
  traceRunId: string | null;
  answered: boolean;
}

export interface RagDocumentSummary {
  id: number;
  filename: string;
  sourceType: string;
  status: 'PENDING' | 'INDEXING' | 'INDEXED' | 'QUARANTINED' | 'FAILED';
  statusDetail: string | null;
  indexedChunks: number;
  quarantinedChunks: number;
  createdAt: string;
  indexedAt: string | null;
}

export interface RagAgentConfigDto {
  version: number;
  systemPrompt: string;
  model: string;
  temperature: number;
  k: number;
  distanceThreshold: number;
}

export interface TelegramSettingsDto {
  botTokenMasked: string | null;
  botTokenSet: boolean;
  chatId: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface SquareConnectionDto {
  accessTokenMasked: string | null;
  accessTokenSet: boolean;
  environment: 'SANDBOX' | 'PRODUCTION' | null;
  locationId: string | null;
  applicationId: string | null;
  merchantId: string | null;
  connectedAt: string | null;
  lastSyncAt: string | null;
  webhookSignatureKeyMasked: string | null;
  webhookSignatureKeySet: boolean;
  // The exact URL to paste into this business's own Square Developer Dashboard webhook
  // subscription (payment.updated) — purely informational, computed server-side, never stored.
  webhookNotificationUrl: string;
}

// accessToken/webhookSignatureKey omitted/undefined = keep the existing value (only meaningful
// when reconnecting to change just the location/environment, or updating one field without
// touching the other); accessToken is required the first time this business connects. Never send
// either field's masked value from SquareConnectionDto back here.
export interface SquareConnectionUpdateRequest {
  accessToken?: string;
  environment: 'SANDBOX' | 'PRODUCTION';
  locationId: string;
  applicationId?: string;
  webhookSignatureKey?: string;
}

// --- SEO monitoring (com.salonreview.seo / web.SeoConnectionController, SeoDashboardController) ---

export interface SeoConnectionDto {
  serviceAccountEmail: string | null;
  serviceAccountSet: boolean;
  ga4PropertyId: string | null;
  ga4MeasurementId: string | null;
  pagespeedApiKeyMasked: string | null;
  pagespeedApiKeySet: boolean;
  connectedAt: string | null;
  lastSyncAt: string | null;
  lastSyncError: string | null;
}

// gscServiceAccountJson/pagespeedApiKey omitted/undefined = keep the existing value — required the
// first time this business connects. Never send serviceAccountEmail/pagespeedApiKeyMasked back here.
export interface SeoConnectionUpdateRequest {
  gscServiceAccountJson?: string;
  ga4PropertyId: string;
  ga4MeasurementId: string;
  pagespeedApiKey?: string;
}

// --- Site tracking config (com.salonreview.tracking / web.TrackingSettingsController) ---
// Microsoft Clarity project id per public site this business owns — see TrackingConfigService's
// own doc for why sites are keyed by hostname, not a per-business singleton (a business can own
// more than one public site, e.g. akluxnails.com and mani.akluxnails.com are both business 1).

export interface TrackingSiteDto {
  hostname: string;
  siteLabel: string;
  clarityProjectId: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface SeoTrendPoint {
  date: string;
  clicks: number;
  impressions: number;
  ctr: number;
  position: number;
}

export interface SeoKeywordRow {
  query: string;
  clicks: number;
  impressions: number;
  ctr: number;
  position: number;
}

export interface SeoAnalyticsPoint {
  date: string;
  totalUsers: number;
  newUsers: number;
  organicSessions: number;
}

// previousPosition/currentPosition/positionDelta are null when there's no data for one half of the
// window yet. positionDelta = previousPosition - currentPosition: positive means the query moved
// to a numerically lower (better) position, i.e. improved. autoSuggested is true when this came
// from the impressions-ranked fallback rather than a query the owner explicitly pinned.
export interface SeoTrackedQueryRow {
  query: string;
  previousPosition: number | null;
  currentPosition: number | null;
  positionDelta: number | null;
  currentImpressions: number;
  autoSuggested: boolean;
}

// null when no PageSpeed snapshot exists yet for that strategy (feature just turned on, first
// weekly check hasn't run).
export interface SeoCoreWebVitals {
  date: string;
  performanceScore: number | null;
  lcpMs: number | null;
  cls: number | null;
  fcpMs: number | null;
  tbtMs: number | null;
}

export interface SeoIssueRow {
  issueType: 'LCP' | 'CLS' | 'INP' | 'FCP' | 'TBT' | 'CTR_OPPORTUNITY';
  severity: 'NEEDS_IMPROVEMENT' | 'POOR' | 'ADVISORY';
  detail: string;
  url: string | null;
  query: string | null;
}

// previous is null when there's no data for the equivalent prior period yet — the UI omits the
// comparison entirely rather than showing a fabricated baseline.
export interface SeoPeriodComparison {
  current: SeoTrendPoint;
  previous: SeoTrendPoint | null;
}

// positionDelta = previousPosition - currentPosition: positive means improved (same sign
// convention as SeoTrackedQueryRow).
export interface SeoQueryChange {
  query: string;
  previousPosition: number;
  currentPosition: number;
  positionDelta: number;
  previousImpressions: number;
  currentImpressions: number;
  previousClicks: number;
  currentClicks: number;
}

export interface SeoOpportunity {
  query: string;
  currentPosition: number;
  currentImpressions: number;
  currentCtr: number;
  reason: 'STRIKING_DISTANCE' | 'HIGH_IMPRESSIONS_LOW_CTR' | 'GROWING_IMPRESSIONS';
}

// changeRatio = (current - previous) / previous: positive means growth.
export interface SeoPageChange {
  page: string;
  previousImpressions: number;
  currentImpressions: number;
  previousClicks: number;
  currentClicks: number;
  changeRatio: number;
}

export interface SeoPageOpportunity {
  page: string;
  currentPosition: number;
  currentImpressions: number;
}

export interface SeoPageShare {
  page: string;
  impressions: number;
  share: number;
  position: number;
}

// pages is sorted by impressions descending — the first entry can be shown as the presumed
// "intended" page without asserting it as fact.
export interface SeoCannibalizedQuery {
  query: string;
  pages: SeoPageShare[];
}

// device is SeoTrackedKeyword.Device's name ("MOBILE"/"DESKTOP"). No rank data — real SERP
// position tracking would need a paid provider, which the owner declined (2026-09-02); this stays
// a curated watchlist only, distinct from both SeoTrackedQueryRow (Search-Console-impressions-
// derived) and the trend chart's own average position.
export interface SeoTrackedKeywordRow {
  id: number;
  keyword: string;
  targetUrl: string | null;
  location: string;
  device: 'MOBILE' | 'DESKTOP';
  active: boolean;
}

// --- Competitors (com.salonreview.web.SeoCompetitorController) — zero-cost scope (2026-09-02) ---
// gbpRating/gbpReviewCount/gbpUpdatedAt are owner-entered (no free API for a competitor's own GBP
// data); latestMobile/latestDesktop come from the existing PageSpeed Insights integration, which
// scores any public URL for free. Keyword-overlap/backlink comparison is intentionally absent —
// that would need a paid SEO tool the owner declined.
export interface SeoCompetitorRow {
  id: number;
  name: string;
  website: string;
  location: string | null;
  notes: string | null;
  active: boolean;
  gbpRating: number | null;
  gbpReviewCount: number | null;
  gbpUpdatedAt: string | null;
  latestMobile: SeoCoreWebVitals | null;
  latestDesktop: SeoCoreWebVitals | null;
}

export interface SeoOverviewDto {
  connected: boolean;
  lastSyncAt: string | null;
  lastSyncError: string | null;
  trend: SeoTrendPoint[];
  analyticsTrend: SeoAnalyticsPoint[];
  topQueries: SeoKeywordRow[];
  trackedQueries: SeoTrackedQueryRow[];
  mobile: SeoCoreWebVitals | null;
  desktop: SeoCoreWebVitals | null;
  activeIssues: SeoIssueRow[];
  last7Days: SeoPeriodComparison | null;
  last28Days: SeoPeriodComparison | null;
  yearOverYear: SeoPeriodComparison | null;
  gainers: SeoQueryChange[];
  losers: SeoQueryChange[];
  opportunities: SeoOpportunity[];
  winningPages: SeoPageChange[];
  losingPages: SeoPageChange[];
  underperformingPages: SeoPageOpportunity[];
  contentOpportunities: SeoPageOpportunity[];
  cannibalizedQueries: SeoCannibalizedQuery[];
  trackedKeywords: SeoTrackedKeywordRow[];
}

export interface BusinessSettingsDto {
  businessId: number;
  name: string;
  shortCode: string;
  timezone: string;
  configured: boolean;
  ownerShortName: string | null;
  baseCommissionRate: number | null;
  tierEnabled: boolean;
  tierServiceThreshold: number | null;
  servicePriceCutoff: number | null;
  cardTipFeeRate: number | null;
  // Phase 4.4: null = this business runs no no-show fee program.
  noShowFeeAmount: number | null;
  // false (default) = every Square discount is absorbed into the provider's commission basis, same
  // as always. true = only discounts matching coveredDiscountNames are absorbed; every other
  // discount reduces the provider's commission basis to what was actually collected.
  restrictDiscountCoverage: boolean;
  coveredDiscountNames: string | null;
  // null on any one of these three = the checkout_review_request automation stays off for this
  // business — see CheckoutReviewTriggerService/CheckoutReviewLinks. Every other business's Google
  // review page, Yelp review page, and feedback form is its own, never AK.LUX.NAILS's.
  googleReviewUrl: string | null;
  yelpReviewUrl: string | null;
  feedbackFormUrl: string | null;
}

// shortCode is immutable, not included. Every other field null/undefined = leave unchanged on an
// existing config — see BusinessSettingsService's own doc for which are required on first setup.
export interface BusinessSettingsUpdateRequest {
  name?: string;
  timezone?: string;
  ownerShortName?: string;
  baseCommissionRate?: number;
  tierEnabled?: boolean;
  tierServiceThreshold?: number;
  servicePriceCutoff?: number;
  cardTipFeeRate?: number;
  noShowFeeAmount?: number;
  restrictDiscountCoverage?: boolean;
  coveredDiscountNames?: string;
  googleReviewUrl?: string;
  yelpReviewUrl?: string;
  feedbackFormUrl?: string;
}

export interface PlatformBusinessDto {
  id: number;
  name: string;
  shortCode: string;
  timezone: string;
  active: boolean;
  createdAt: string;
}

export interface CreateBusinessRequest {
  name: string;
  shortCode: string;
  timezone: string;
  ownerUsername: string;
  ownerPassword: string;
}

// null field = leave unchanged; '' = clear. Never send the masked token back — only include
// botToken when the owner actually typed a new one.
export interface TelegramSettingsUpdateRequest {
  botToken?: string | null;
  chatId?: string | null;
}

export interface TwilioSmsSettingsDto {
  accountSidMasked: string | null;
  accountSidSet: boolean;
  apiKeyMasked: string | null;
  apiKeySet: boolean;
  apiSecretMasked: string | null;
  apiSecretSet: boolean;
  fromPhoneNumber: string | null;
  // Who every automated (and AI-drafted) SMS signs as — e.g. "It's Lucy from AK.LUX.NAILS 💛".
  senderName: string;
  updatedAt: string | null;
  updatedBy: string | null;
}

// null field = leave unchanged; '' = clear. Never send masked accountSid/apiKey/apiSecret back —
// only include a field when the owner actually typed a new value. senderName is NOT NULL on the
// backend — a blank submission there is simply ignored, not cleared.
export interface TwilioSmsSettingsUpdateRequest {
  accountSid?: string | null;
  apiKey?: string | null;
  apiSecret?: string | null;
  fromPhoneNumber?: string | null;
  senderName?: string | null;
}

export interface MailchimpSettingsDto {
  apiKeyMasked: string | null;
  apiKeySet: boolean;
  audienceId: string | null;
  fromName: string | null;
  fromEmail: string | null;
  replyToEmail: string | null;
  configured: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

// SPF/DKIM/DMARC/MX health for this business's own sending domain (the domain half of
// MailchimpSettingsDto.fromEmail) — see EmailDomainHealthService. `configured: false` means no
// from_email is set yet; every other field is null and no DNS lookups ran.
export interface EmailDomainHealthCheckDto {
  pass: boolean;
  detail: string;
}

export interface EmailDomainHealthDto {
  configured: boolean;
  domain: string | null;
  score: number | null;
  rating: string | null;
  spf: EmailDomainHealthCheckDto | null;
  dkim: EmailDomainHealthCheckDto | null;
  dmarc: EmailDomainHealthCheckDto | null;
  mx: EmailDomainHealthCheckDto | null;
  checkedAt: string | null;
}

// null field = leave unchanged; '' = clear. Never send the masked apiKey back — only include a
// field when the owner actually typed a new value.
export interface MailchimpSettingsUpdateRequest {
  apiKey?: string | null;
  audienceId?: string | null;
  fromName?: string | null;
  fromEmail?: string | null;
  replyToEmail?: string | null;
}

// com.salonreview.web.MailchimpActivityController — the win-back email fallback's activity log:
// which email went to which customer, when, whether they opened/clicked it, and whether they
// actually came back (a real completed visit, not just a click).
export interface MailchimpActivitySendView {
  id: number;
  automationKey: string;
  emailAddress: string | null;
  state: string;
  sentAt: string;
  openedAt: string | null;
  clickedAt: string | null;
  converted: boolean;
}

export interface MailchimpActivityStats {
  windowDays: number;
  sentCount: number;
  openedCount: number;
  clickedCount: number;
  convertedCount: number;
  openRate: number;
  clickRate: number;
  conversionRate: number;
}

export interface MailchimpActivityResponse {
  sends: MailchimpActivitySendView[];
  stats: MailchimpActivityStats;
}

// --- SMS message templates (com.salonreview.sms.SmsMessageTemplateCatalog /
// com.salonreview.web.SmsTemplateSettingsController) — the owner-editable wording behind every
// automated SMS. automationKey is null for a template not tied to any owner-toggleable automation.
// One rotating-wording slot of a template — a key with variants.length > 1 rotates through all of
// them automatically (a repeat customer sees a different one each time instead of an identical
// script every visit) so a repeat customer doesn't see the same text every time — see backend
// SmsMessageTemplateCatalog's own doc. Editing one slot only overrides that slot; the rest keep
// rotating on their own in-code defaults.
export interface SmsTemplateVariantView {
  index: number;
  body: string;
  customized: boolean;
}

export interface SmsTemplateView {
  key: string;
  automationKey: string | null;
  label: string;
  variables: string[];
  variants: SmsTemplateVariantView[];
}

// --- Coupon discount terms (com.salonreview.sms.PromoConfigService / PromoSettingsController) ---
// null discountAmount/minSpend + configured=false means this business hasn't set this promo up in
// Square yet — same_day_rebooking_discount/lapsed_customer_winback stay effectively inert until it
// does (see ShortLinkController — the coupon link 404s rather than pointing at nothing).
export interface PromoTermsDto {
  promoCode: string;
  automationKey: string;
  label: string;
  discountAmount: number | null;
  minSpend: number | null;
  configured: boolean;
}

// --- Service lifecycle roles (com.salonreview.web.ServiceLifecycleRoleController) ---
// Which Square service plays which role (touch-up, color booster, ...) in this business's own
// customer service lifecycle — owner-editable, never hardcoded; see ServiceLifecycleRole's own doc
// for why role is a free string, not a fixed set, and why squareVariationId (not shown raw to the
// owner — see displayName) has to be a real Square variation id, picked via search, not typed.
export interface ServiceLifecycleRoleDto {
  id: number;
  role: string;
  squareVariationId: string;
  displayName: string;
  createdBy: string | null;
}

export interface CatalogSearchResultDto {
  variationId: string;
  displayName: string;
  // Opens this exact item in the Square Seller Dashboard, so the owner can check it (e.g. tell
  // apart a real item from a same-named duplicate) before picking it — see SquareClient's own doc.
  dashboardUrl: string;
}

// --- SMS automations hub (com.salonreview.sms / com.salonreview.web.Sms*Controller) ---

// tracksClicks/tracksReplies/tracksConversion say whether a click-through / reply / "customer
// actually came back" rate is even meaningful for this automation (some automations never include
// a link, never ask for a reply, or have no measurable real-world outcome) — see
// SmsAutomationRegistry.AutomationMeta's own doc on the backend. When false, the paired count is
// always 0 and the UI should omit that stat entirely rather than show a misleading "0%".
// convertedLast30Days is the numerator; sentLast30Days is the shared denominator, same convention
// as replyLast30Days.
export interface SmsAutomationSummary {
  key: string;
  name: string;
  audienceDescription: string;
  enabled: boolean;
  sentLast30Days: number;
  tracksClicks: boolean;
  linkSentLast30Days: number;
  clickedLast30Days: number;
  tracksReplies: boolean;
  replyLast30Days: number;
  tracksConversion: boolean;
  convertedLast30Days: number;
  // The email fallback leg (see WinbackEmailFallbackScheduler) — only lapsed_customer_winback and
  // repeat_customer_winback set tracksEmail true. A distinct channel from the SMS click/reply
  // stats above, shown as its own line — conversion isn't split by channel (see
  // SmsAutomationService#list's own doc), so there's no separate emailConverted count.
  tracksEmail: boolean;
  emailSentLast30Days: number;
  emailOpenedLast30Days: number;
  emailClickedLast30Days: number;
  // Whether required config (a coupon, review links, lifecycle-role services, ...) is present —
  // separate from `enabled`. false blocks turning the toggle ON (never blocks turning it OFF) —
  // see AutomationReadinessService.
  ready: boolean;
  readinessReason: string | null;
}

export type SmsMessageDirection = 'OUTBOUND' | 'INBOUND';

// Twilio's per-message delivery-status callback value — "queued" | "sending" | "sent" |
// "delivered" | "undelivered" | "failed" — or null if no callback has arrived yet (or the
// message predates this tracking). Distinct from SmsMessageDto.status, which only reflects
// whether our own send attempt to Twilio succeeded.
export type SmsDeliveryStatus = string | null;

// One MMS photo attached to a message — see SmsActivityController.SmsMediaDto. url is a public,
// unauthenticated /api/public/sms-media/{token} link (safe to use directly in an <img src>).
export interface SmsMediaDto {
  url: string;
  contentType: string;
}

// The customer's emoji reaction on a message — an Apple tapback-over-SMS text (e.g.
// `Loved "..."`), matched back to it — see SmsActivityController.SmsReactionDto.
export interface SmsReactionDto {
  emoji: string;
}

// The evening email follow-up tied to one specific outbound SMS (see
// SmsActivityController.EmailFollowUpDto / WinbackEmailFallbackScheduler) — only present on the
// SMS that was a candidate for one. state is 'SENT' or one of the SKIPPED_*/SEND_FAILED reasons;
// contentHtml is only ever set for 'SENT'.
export interface EmailFollowUpDto {
  state: string;
  emailAddress: string | null;
  sentAt: string;
  openedAt: string | null;
  clickedAt: string | null;
  contentHtml: string | null;
}

export interface SmsMessageDto {
  id: number;
  direction: SmsMessageDirection;
  automationKey: string | null;
  phoneNumber: string;
  templateKey: string | null;
  body: string;
  status: string;
  reason: string | null;
  linkTarget: string | null;
  clickedAt: string | null;
  readAt: string | null;
  createdAt: string;
  deliveryStatus: SmsDeliveryStatus;
  deliveryErrorMessage: string | null;
  deliveryUpdatedAt: string | null;
  media: SmsMediaDto[];
  reactions: SmsReactionDto[];
  emailFollowUp: EmailFollowUpDto | null;
}

// One conversation (grouped by phone number) in the manager-facing /admin/messages inbox — see
// openspec/changes/lead-followup-and-manager-inbox design.md D8.
export interface SmsConversationDto {
  phoneNumber: string;
  lastMessageAt: string;
  lastMessageBody: string;
  lastMessageDirection: SmsMessageDirection;
  unreadCount: number;
  givenName: string | null;
  familyName: string | null;
  /** True if consent for SMS marketing comes from *either* source: this app's own
   * marketing.contacts capture, or the customer belonging to Square's own consent segment — see
   * MarketingContactsService#resolveDisplayNames on the backend. */
  smsConsent: boolean;
  /** Square Dashboard customer profile link, or null if no Square customer could be resolved for
   * this phone number at all (not even via a live phone lookup) — more permissive than
   * MarketingContact's own squareProfileUrl, which requires a marketing.contacts row to exist. */
  squareProfileUrl: string | null;
  lastMessageDeliveryStatus: SmsDeliveryStatus;
  lastMessageDeliveryErrorMessage: string | null;
  /** True if this phone number has *ever* replied to the checkout-review-request automation with
   * a low (1-4) star rating — permanent once true, so it stays true even once the conversation
   * moves on to friendlier messages. Same phone number is permanently excluded from the
   * same-day-rebooking win-back nudge on the backend. */
  hasNegativeFeedback: boolean;
  /** True once this Square customer's distinct-day visit count reaches the configured VIP
   * threshold — same computation as MarketingContact#vip, resolved through the same phone ->
   * customer id ladder as smsConsent/squareProfileUrl above. Always false when no Square
   * customer could be resolved for this phone number at all. */
  vip: boolean;
  /** The distinct-day visit count backing `vip`, or null when no Square customer is known. */
  visitCount: number | null;
  /** True if a manager has blocked this number — TwilioSmsService silently refuses to send it
   * any further outbound SMS (automated or manual), and inbound texts no longer trigger a
   * Telegram alert (still logged, just no longer paged). */
  blocked: boolean;
  /** True if `blocked` is true *because* the customer texted a standard opt-out keyword
   * (STOP/UNSUBSCRIBE/...), as opposed to a manager choosing "Block number" — see
   * BlockedNumber#SOURCE_STOP_REQUEST on the backend. Always false when `blocked` is false. */
  optedOut: boolean;
  /** True if this phone number has *ever* clicked through the checkout-review-request
   * automation's Google review link — quick-glance version of the fuller sent/clicked/date
   * detail already shown in the contact info panel (see MarketingContact's own
   * googleReviewClickedAt). Once both this and clickedFeedbackForm are true, the backend stops
   * sending new review-request asks to this number (see CheckoutReviewTriggerService). */
  clickedGoogleReview: boolean;
  /** Same as clickedGoogleReview, for the checkout-review automation's middle escalation rung —
   * a Yelp-review ask, sent only to a contact who already left a Google review. */
  clickedYelpReview: boolean;
  /** Same as clickedGoogleReview, for the feedback-form link. */
  clickedFeedbackForm: boolean;
  /** True if any outbound message to this number has ever come back with Twilio delivery-status
   * error code 30007 ("Filtered as spam by carrier") or 21610 ("Recipient has opted out — replied
   * STOP") — quick-glance version of the fuller "Not delivered — <reason>" detail already shown
   * on the individual message bubble. See SmsMessageLogService#phoneNumbersFlaggedAsSpam. */
  flaggedAsSpam: boolean;
}

// One cursor-paginated page of SmsConversationDto — see SmsActivityController.ConversationPageDto.
// nextCursor is the ISO-8601 lastMessageAt of the last item, to pass back as the next request's
// cursor; null when items is empty. hasMore is a hint, exact in the common case (true only when a
// full page came back).
export interface SmsConversationPageDto {
  items: SmsConversationDto[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface SmsReplyResult {
  sent: boolean;
  reason: string | null;
}

// AI-drafted reply suggestion for the manager conversation view's "Generate" button — see
// SmsActivityController#draftReply. A draft only; the manager reviews/edits it in the composer
// before actually sending via api.sendSmsReply.
export interface SmsDraftResult {
  body: string;
  model: string;
}

// One phone number's most-recent matching message for the manager conversation view's search
// box — content matches only; name/phone matches are found client-side against the already-
// loaded conversation list (see MessagesView.tsx).
export interface SmsConversationSearchHitDto {
  phoneNumber: string;
  snippet: string;
  direction: SmsMessageDirection;
  matchedAt: string;
}

export interface Provider {
  id: number;
  name: string;
  displayName: string;
  commissionRate: number;
  cardTipFeeRate: number;
  active: boolean;
}

export interface PayPeriod {
  id: number;
  year: number;
  month: number;
  half: Half;
  label: string;
}

export interface PeriodEntry {
  id: number;
  providerId: number;
  providerDisplayName: string;
  payPeriodId: number;
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  adjustmentsAmount: number;
  adjustmentsNote: string | null;
  commissionRate: number | null;
}

export interface PayPeriodDetail {
  period: PayPeriod;
  entries: PeriodEntry[];
}

export interface Settlement {
  providerId: number;
  providerName: string;
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  tipsAfterFee: number;
  adjustments: number;
  zelleToProvider: number;
  cashToSalon: number;
  messageText: string;
}

export interface PeriodEntryUpsertRequest {
  procedures: number;
  cardTotal: number;
  cashTotal: number;
  cardTips: number;
  adjustmentsAmount: number;
  adjustmentsNote?: string | null;
  commissionRate?: number | null;
}

export interface PayPeriodCreateRequest {
  year: number;
  month: number;
  half: Half;
}

// --- Square-sourced settlement preview (com.salonreview.square.SettlementPreviewService) ---

export interface CommissionConfig {
  tierServiceThreshold: number;
  baseRate: number;
  tierRate: number;
  cardTipFeeRate: number;
  tierEnabled: boolean;
}

export interface HalfSettlement {
  half: Half;
  stage: 'PROVISIONAL_FIRST_HALF' | 'FINAL_MONTH_CLOSE';
  countedServices: number;
  appliedRate: number;
  cardRevenue: number;
  cashCollected: number;
  tipsAfterFee: number;
  adjustments: number;
  tierBonus: number;
  cashTierRebate: number;
  zelleToProvider: number;
  cashToSalon: number;
}

export interface ProviderPayout {
  providerId: number;
  name: string;
  monthCountedServices: number;
  autoQualified: boolean;
  tierManuallyGranted: boolean;
  tierApplied: boolean;
  firstHalf: HalfSettlement;
  secondHalf: HalfSettlement;
  monthZelleToProvider: number;
  monthCashToSalon: number;
  // Provider's response per period (null until they act). Owner/manager see it on the report.
  firstFeedback: Feedback | null;
  secondFeedback: Feedback | null;
  // Copy-pasteable #salary block per half (null if no activity that half).
  firstHalfMessage: string | null;
  secondHalfMessage: string | null;
  // Owner+manager badge counts — every uncleared suspicious booking, per half.
  firstHalfSuspicious: number;
  secondHalfSuspicious: number;
  // Provider self-view badge counts — only bookings with no notes at all (their actionable subset).
  firstHalfSuspiciousNoNotes: number;
  secondHalfSuspiciousNoNotes: number;
  // Owner-only warning counts — uncleared provider cancellations per half (informational only).
  firstHalfCancellations: number;
  secondHalfCancellations: number;
}

export interface ServiceLine {
  name: string | null;
  gross: number | null;
}

export interface SuspiciousBooking {
  bookingId: string;
  date: string;           // yyyy-MM-dd
  time: string;           // h:mm a salon-local
  customerId: string;
  customerName: string | null;
  serviceName: string | null;  // combined "A + B + C", convenience fallback
  gross: number | null;        // summed across services
  // Per-segment breakdown — render as chips for at-a-glance scanning. Empty array (not null)
  // when no services resolved.
  services: ServiceLine[];
  half: 'FIRST' | 'SECOND';
  // Free-text notes attached to the appointment in Square (nullable / blank-normalized to null).
  sellerNote: string | null;
  customerNote: string | null;
  cleared: boolean;
  clearedBy: string | null;
  clearedAt: string | null;   // ISO instant
  clearedNote: string | null;
  // Cached AI triage under the current prompt version (null when no triage exists yet).
  triage: TriageResult | null;
}

// A cancelled appointment (CANCELLED_BY_SELLER) surfaced for owner review — confirm on camera that
// no procedure was done. Shape mirrors SuspiciousBooking (minus AI triage).
export interface CancelledAppointment {
  bookingId: string;
  date: string;           // yyyy-MM-dd
  time: string;           // h:mm a salon-local
  customerId: string;
  customerName: string | null;
  serviceName: string | null;
  gross: number | null;
  services: ServiceLine[];
  half: 'FIRST' | 'SECOND';
  sellerNote: string | null;
  customerNote: string | null;
  cleared: boolean;
  clearedBy: string | null;
  clearedAt: string | null;   // ISO instant
  clearedNote: string | null;
}

export interface Feedback {
  status: FeedbackStatus;
  comment: string | null;
}

// --- Manager time tracking ---

// One worked shift. `open` = currently clocked in (endAt/endLabel null, minutes 0).
export interface TimeEntry {
  id: number;
  workDate: string;          // yyyy-MM-dd (salon-local)
  half: 'FIRST' | 'SECOND';
  startAt: string;           // ISO instant
  endAt: string | null;
  startLabel: string;        // "9:00 AM" salon-local
  endLabel: string | null;
  minutes: number;           // 0 while open
  open: boolean;
  note: string | null;
}

// A manager's own calendar month. Pay fields are null until the owner sets a rate.
export interface ManagerTimesheet {
  year: number;
  month: number;
  timezone: string;
  usdPerHour: number | null;
  monthMinutes: number;
  monthPay: number | null;
  entries: TimeEntry[];      // completed shifts this month, start ascending
  open: TimeEntry | null;    // the currently-open shift, if any
}

// Input for adding/editing a manual shift (date + start/end are salon-local, HH:mm).
export interface TimeEntryInput {
  date: string;
  startTime: string;
  endTime: string;
  note?: string | null;
}

export interface AdminTimesheetRow {
  userId: number;
  username: string;
  email: string | null;
  usdPerHour: number | null;
  monthMinutes: number;
  monthPay: number | null;
  clockedIn: boolean;
}

export interface AdminTimesheet {
  year: number;
  month: number;
  timezone: string;
  managers: AdminTimesheetRow[];
}

// Owner's day-by-day schedule view: every manager's shifts on a timeline per day, plus computed
// anomaly flags (mistyped clock-in/out, coverage gaps, missing handoff overlap). See
// ManagerTimeService.adminDailySchedule (backend) for the flag codes' exact meaning.
export interface AdminScheduleShift {
  id: number;
  userId: number;
  username: string;
  startAt: string;            // ISO instant
  endAt: string | null;       // null while open
  startLabel: string;         // "8:00 AM" salon-local
  endLabel: string | null;
  minutes: number;            // 0 while open
  open: boolean;
  flags: string[];            // e.g. "start_way_off", "too_short", "still_open"
}

export interface AdminScheduleDay {
  date: string;                // yyyy-MM-dd
  shifts: AdminScheduleShift[]; // start-time ascending
  coverageMinutes: number;      // minutes in [8am,8pm) with >=1 manager clocked in
  overlapMinutes: number;       // minutes in [8am,8pm) with >=2 managers clocked in concurrently
  flags: string[];              // e.g. "gap_in_coverage", "no_overlap", "no_shifts"
}

export interface AdminDailySchedule {
  year: number;
  month: number;
  timezone: string;
  expectedStartLabel: string;   // "8:00 AM"
  expectedEndLabel: string;     // "8:00 PM"
  expectedOverlapMinutes: number; // 60
  days: AdminScheduleDay[];     // newest date first
}

// --- Accounts & roles (Phase 2) ---

export type Role = 'OWNER' | 'MANAGER' | 'PROVIDER' | 'ADS_MANAGER';

export type FeedbackStatus = 'APPROVED' | 'CHANGES_REQUESTED';

export type Language = 'EN' | 'RU';

// One business the caller can switch into (design.md D12) — see AdminMenu's switcher row.
export interface MeBusinessOption {
  id: number;
  name: string;
}

export interface Me {
  username: string;
  role: Role;
  providerId: number | null;
  // null until an owner/manager has chosen — the frontend uses that to show the one-time prompt.
  preferredLanguage: Language | null;
  features: Features;
  // Phase 6.1/6.2 (design.md D12): the business CurrentBusinessContext is scoped to for this
  // request, session-switch-aware (not necessarily the login-time default). `businesses` is the
  // switcher's own option list — every active business for a platform_admin, otherwise just the
  // caller's own real membership(s) (today always exactly one, so AdminMenu renders plain text,
  // not a dropdown, for that case).
  activeBusinessId: number;
  businesses: MeBusinessOption[];
}

export interface Features {
  aiTriageEnabled: boolean;
  // Phase 4.3: whether the RAG assistant (chat widget + admin corpus) is on for this specific
  // business, not just deployment-wide — see BusinessFeatureService on the backend.
  ragEnabled: boolean;
  ragSuggestionsEnabled: boolean;
  ragFollowupsEnabled: boolean;
  // seo-monitoring-dashboard design.md D6: whether /owner/marketing/seo + /owner/settings/seo
  // should render at all for this business — purely business-gated, no deployment-level flag.
  seoMonitoringEnabled: boolean;
  // seo-intelligence-advisor Phase 6: whether the "Analyze SEO" advisor section renders within
  // the already-gated SEO tab — both deployment-level AND business-gated (unlike seoMonitoringEnabled
  // above, since this one calls Claude).
  aiSeoAdvisorEnabled: boolean;
}

// Knowledge-gap requests (com.salonreview.web.KbRequestController).
export type KbRequestTarget = 'KB' | 'SOP' | 'UNSURE';
export type KbRequestStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED';

export interface KbRequest {
  id: number;
  question: string;
  note: string | null;
  target: KbRequestTarget;
  status: KbRequestStatus;
  requestedBy: string;
  createdAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
}

// Grounded starter prompts for the assistant's empty state (com.salonreview.rag.StarterSuggestions).
export interface StarterTopic {
  label: string;
  questions: string[];
}

export interface StarterSuggestions {
  topics: StarterTopic[];
}

// --- AI triage (suspicious-booking explainer) ---
// Mirrors TriageResult / TriageClassification in com.salonreview.ai.

export type TriageClassification = 'LIKELY_LEGIT' | 'NEEDS_REVIEW' | 'LIKELY_FRAUD';

export interface TriageResult {
  classification: TriageClassification;
  // 0.0 to 1.0 — sent as a JSON number on the wire.
  confidence: number;
  explanation: string;
  // Empty string when classification is LIKELY_LEGIT (no message needed when nothing's wrong).
  draftMessage: string;
  // Detection-signal names the explanation cites (e.g. "past_appointment_no_order").
  signals: string[];
  promptVersion: string;
  model: string;
}

export interface TriageFeedbackRequest {
  helpful: boolean;
  // Owner's corrected classification on a thumbs-down; null when no correction was provided.
  correctedClassification: TriageClassification | null;
}

export interface AppUser {
  id: number;
  username: string;
  role: Role;
  providerId: number | null;
  active: boolean;
  squareTeamMemberId: string | null;
  email: string | null;
}

export interface UserCreateRequest {
  username: string;
  password: string;
  role: Role;
  providerId?: number | null;
  squareTeamMemberId?: string | null;
  email?: string | null;
  name?: string | null;
}

// A Square team member offered as a candidate account in the add-user flow.
export interface SquareRosterEntry {
  teamMemberId: string;
  name: string;
  email: string | null;
  jobTitle: string | null;
  isOwner: boolean;
  suggestedRole: Role;
  providerId: number | null;
  hasAccount: boolean;
}

export interface UserUpdateRequest {
  role?: Role | null;
  active?: boolean | null;
  password?: string | null;
  providerId?: number | null;
}

// --- Per-provider line-level trace (owner/manager drill-down) ---

export interface AttributedService {
  providerId: string; // Square team-member id
  providerName: string;
  date: string;
  half: Half;
  service: string;
  gross: number;
  discount: number;
  net: number;
  tip: number; // this line's share of the transaction (order) tip
  counted: boolean;
  countedUnits: number; // main services this line counts toward the tier (gross >= cutoff)
  units: number; // total services this line represents (incl. add-ons below the cutoff)
  prepaid: boolean;
  channel: 'CARD' | 'CASH' | 'CASH-NOTE' | 'PREPAID' | 'COMP' | 'REDO' | 'MANUAL' | 'NOSHOW';
  time: string | null; // appointment start, salon-local, e.g. "2:30 PM"
  bookingId: string | null; // Square booking/reservation id (for the appointment link)
  customer: string | null; // short client name, e.g. "Donnah P."
}

// A no-show row — one per provider on the booking (a multi-provider no-show splits the fee).
// state: CREDITED (fee detected/paid), NO_FEE (no fee collected), SUPPRESSED (auto-match dismissed),
// CONFIRMED (manager credited an off-signal fee).
export interface NoShowRow {
  bookingId: string;
  providerId: number;
  providerName: string;
  customer: string | null;
  noShowAt: string; // ISO instant of the appointment
  noShowDate: string; // salon-local date (YYYY-MM-DD)
  feeAmount: number | null; // the $25 fee when paid/credited, else null
  feePaidDate: string | null;
  state: 'CREDITED' | 'NO_FEE' | 'SUPPRESSED' | 'CONFIRMED';
}

export interface UnmatchedLine {
  date: string;
  service: string;
  gross: number;
  channel: 'CARD' | 'CASH';
  customerId: string | null;
  customerName: string | null;
}

// A completed Square payment with no linked Order at all (e.g. a card charged directly against a
// customer's card on file, bypassing the booking checkout) — the order-based reconciliation never
// sees these. Never folded into revenue/commission automatically; `suggested*` is a starting point
// for the owner/manager to confirm via a Manual Adjustment, not an attribution.
export interface OrphanPayment {
  date: string;
  amount: number;
  customerId: string | null;
  customerName: string | null;
  suggestedProviderId: string | null;
  suggestedProviderName: string | null;
  suggestedBookingId: string | null;
  note: string;
}

export interface ProviderDetail {
  year: number;
  month: number;
  providerId: number;
  name: string | null;
  payout: ProviderPayout | null;
  services: AttributedService[];
  unmatched: UnmatchedLine[];
  orphanPayments: OrphanPayment[];
  firstHalfMessage: string | null;
  secondHalfMessage: string | null;
  priceCutoff: number; // a "main service" is gross >= this (e.g. $60)
  timezone: string;
  syncedAt: string; // ISO instant — when this view was pulled live from Square
  noShows: NoShowRow[]; // this provider's no-shows for the month, with fee status
}

export interface SettlementDiagnostics {
  orders: number;
  matchedLineItems: number;
  prepaidMatches: number;
  unmatchedLineItems: number;
  unmatchedRevenue: number;
  cashNotes: number;
  cashNotesSkipped: number; // notes ignored because the appointment was checked out as cash
  ownerComps: number; // services to an owner/family customer credited at menu price (no order)
  ownerCompsSkipped: number; // owner bookings we couldn't value (no catalog price)
  orphanPayments: number; // completed payments with no linked order at all
  orphanPaymentRevenue: number;
  cashNoteAmountCapped: number; // note's written amount exceeded the catalog price (likely typo)
  cashNoteGapMatches: number;   // cash-note gaps auto-resolved against an unattributed sale
}

// --- Owner/family customers (owner/manager) ---

export interface OwnerCustomer {
  id: number;
  squareCustomerId: string;
  name: string | null;
}

export interface CustomerMatch {
  id: string;
  name: string;
}

export interface SettlementPreview {
  year: number;
  month: number;
  timezone: string;
  config: CommissionConfig;
  priceCutoff: number;
  providers: ProviderPayout[];
  diagnostics: SettlementDiagnostics;
  syncedAt: string; // ISO instant — when this report was pulled live from Square
}

// --- Prepaid packages (owner/manager) ---

export interface PrepaidRedemption {
  id: number;
  squareBookingId: string;
  serviceVariationId: string;
  serviceName: string | null;
  serviceDate: string;
  menuPrice: number;
  counts: boolean;
  providerName: string; // the provider who performed this draw-down
}

export interface PrepaidPackage {
  id: number;
  customerId: string | null;
  customerName: string;
  paidDate: string;
  amount: number;
  totalServices: number;
  redeemed: number;
  balance: number;
  status: 'ACTIVE' | 'CLOSED';
  invoiceRef: string | null;
  redemptions: PrepaidRedemption[];
}

export interface PrepaidCandidate {
  bookingId: string;
  serviceVariationId: string;
  serviceName: string;
  date: string;
  time: string | null;
  menuPrice: number;
  counts: boolean;
  teamMemberId: string; // Square team member who performed it — credited on confirm
  providerName: string;
}

export interface PrepaidInvoice {
  id: string;
  number: string | null;
  title: string | null;
  status: string;
  date: string | null;
  amount: number;
}

// A PAID Square invoice not yet linked to any prepaid package — see PrepaidService#unattributed.
export interface UnattributedInvoice {
  id: string;
  customerId: string | null;
  customerName: string | null;
  number: string | null;
  title: string | null;
  date: string | null;
  amount: number;
}

// --- Redos (owner/manager) ---

export interface Redo {
  id: number;
  originalProviderId: number;
  originalProviderName: string;
  redoProviderId: number;
  redoProviderName: string;
  originalDate: string;
  redoDate: string;
  amount: number;
  serviceName: string | null;
}

export interface RedoCreateRequest {
  originalProviderId: number;
  redoProviderId: number;
  originalDate: string;
  redoDate: string;
  amount: number;
  serviceName?: string | null;
}

// --- Missed bookings (owner/manager) — a quick "no capacity to book this customer" log, for
// later analysis of whether demand justifies hiring another provider. See backend V121.
export interface MissedBooking {
  id: number;
  requestedDate: string;
  requestedTime: string | null;
  estimatedRevenue: number;
  serviceName: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface MissedBookingCreateRequest {
  requestedDate: string;
  requestedTime?: string | null;
  estimatedRevenue: number;
  serviceName?: string | null;
}

// --- Manual settlement adjustments (owner/manager) ---

export interface ManualAdjustment {
  id: number;
  providerId: number;
  providerName: string;
  serviceDate: string;
  gross: number;
  discount: number;
  tip: number;
  serviceName: string | null;
}

export interface ManualAdjustmentCreateRequest {
  providerId: number;
  serviceDate: string;
  gross: number;
  discount: number;
  tip: number;
  serviceName?: string | null;
}

export interface PrepaidCreateRequest {
  customerId?: string | null;
  customerName: string;
  paidDate: string;
  amount: number;
  totalServices: number;
  invoiceRef?: string | null;
}

// --- Revenue pulse: same-period comparison + upcoming projection (owner-only) ---

export interface RevenuePulse {
  year: number;
  month: number;
  currentDays: number;
  currentEndDay: number;
  priorEndDay: number;
  // Wall-clock time cutoff (e.g. "11:22 PM") applied to both current and prior windows when
  // looking at the current month. Null for past months (no time cutoff — full days compared).
  asOfTime: string | null;
  currentGross: number;
  currentCard: number;
  currentCash: number;
  // Earned separately from card/cash — not a tender, not part of currentGross's total.
  currentTip: number;
  priorGross: number;
  priorCard: number;
  priorCash: number;
  priorTip: number;
  deltaPct: number | null;
  // Non-cancelled upcoming bookings remaining this month.
  upcomingBookings: number;
  upcomingGross: number;
  // currentGross + upcomingGross — naive ceiling, kept as a cross-check.
  projectedMonthGross: number;
  // Forecaster output: blends pattern-match (PeriodEntry) and booking-ceiling calibration (snapshots).
  projectedMid: number;
  // projectedMid split by the recent card:cash mix (0 when there's no realized revenue to infer from).
  projectedCard: number;
  projectedCash: number;
  // projectedMid × the recent tip-to-gross ratio (0 when there's no realized revenue to infer from).
  projectedTip: number;
  projectedLow: number | null;        // null in cold-start mode
  projectedHigh: number | null;       // null in cold-start mode
  forecastCalibrationDataPoints: number;
  forecastHistoryMonths: number;
  // Total days in each month — lets the UI flag when the two months differ in length (e.g. May 31 vs
  // June 30), so a clamped like-for-like window is obvious rather than a silent dropped day.
  currentMonthLength: number;
  priorMonthLength: number;
  // What the app was actually projecting on the same day last month — read from that day's stored
  // revenue_snapshot row (real MTD + real booked-ahead pipeline, not a recomputation). Null for
  // past-month views (only meaningful relative to "now"), or when no snapshot exists for that date.
  priorProjected: number | null;
  // (projectedMid − priorProjected) / priorProjected × 100; null when priorProjected is null/zero.
  projectedDeltaPct: number | null;
}

// What was known/projected as of a specific past date (com.salonreview.web.RevenuePulseController#day),
// from that day's frozen daily snapshot plus a forecast recomputed from its stored inputs.
export interface RevenueDayDetail {
  date: string; // ISO yyyy-MM-dd
  // False when no snapshot was ever captured for this date — every other field is null/0 then.
  hasSnapshot: boolean;
  mtdRevenue: number | null;
  mtdCard: number | null;
  mtdCash: number | null;
  mtdServices: number;
  upcomingCount: number;
  upcomingGross: number | null;
  projectedMid: number | null;
  projectedLow: number | null; // null in cold-start mode
  projectedHigh: number | null;
  // The month's actual final total, once settled; null while still open.
  monthEndActual: number | null;
}

// --- Provider retention analytics (owner + manager, view-only for the latter) ---

export interface RetentionTrendPoint {
  year: number;
  month: number;
  clientsSeen: number;
  newToProvider: number;
}

export interface ProviderRetentionRow {
  providerRef: string;
  providerName: string;
  clientsSeen: number;
  newToProvider: number;
  returningToProvider: number;
  newToSalonViaP: number;
  sameDayRebookRate: number | null;
  cohortSize: number;
  providerRetention: number | null;
  salonRetention: number | null;
  cohortMatured: boolean;
  leakRisk: boolean;
  trend: RetentionTrendPoint[];
}

export interface RetentionReport {
  year: number;
  month: number;
  retentionWindowDays: number;
  providers: ProviderRetentionRow[];
}

export interface RetentionSeriesPoint {
  year: number;
  month: number;
  clientsSeen: number;
  newClients: number;
  returningClients: number;
}

export interface RetentionProviderOption {
  ref: string;
  name: string;
}

export interface RetentionSeries {
  fromYear: number;
  fromMonth: number;
  toYear: number;
  toMonth: number;
  providerRef: string | null; // null = all providers
  providers: RetentionProviderOption[];
  points: RetentionSeriesPoint[];
}

// /owner/reviews — every checkout-review-request reply, grouped by provider (see backend V120 /
// CheckoutReviewInsightsService). providerId is null on a review whose flow never resolved a
// provider; rating is null on a reply with no digit in the text at all — both are still real
// reviews, just unattributed/unrated, not dropped.
export interface ReviewView {
  messageId: number;
  providerId: number | null;
  providerName: string | null;
  rating: number | null;
  body: string;
  phoneNumber: string;
  customerName: string | null;
  createdAt: string;
}

export interface ProviderRatingSummary {
  providerId: number;
  providerName: string;
  averageRating: number | null;
  ratedCount: number;
  unratedCount: number;
}

export interface ReviewsOverview {
  averageRating: number | null;
  ratedCount: number;
  totalCount: number;
  byProvider: ProviderRatingSummary[];
  reviews: ReviewView[];
}

// --- Owner overview dashboard (owner-only) ---

export interface MonthSummary {
  year: number;
  month: number;
  label: string;
  cardRevenue: number | null;
  cashRevenue: number | null;
  grossRevenue: number | null;
  tips: number | null;
  procedures: number;
  avgPerAppt: number | null;
  payrollCost: number | null;
  payrollPct: number | null;
  finalized: boolean;
  // Salon-level client counts from the visit ledger (0 when the ledger doesn't yet cover the month).
  clientsSeen: number;
  returningClients: number;
  /** Resolved from the flexible `expense_entries` ledger (see ExpenseResolver), prorated by
   * calendar-day overlap for this month — null when not yet resolvable (same conditions as
   * grossRevenue/payrollCost being null). */
  expenseTotal: number | null;
  /** Manager labor cost for this month: real clocked hours x rate whenever any clocked data
   * exists (manager time tracking started July 2026), otherwise the manual MANAGER_TIME
   * expense-entry backfill for earlier months. Null under the same conditions as expenseTotal. */
  managerLaborCost: number | null;
  /** grossRevenue - payrollCost - expenseTotal - managerLaborCost — null if any of the four is null. */
  netRevenue: number | null;
  /** Whether a COMPLETED bank-statement reconciliation overlaps this month — when true,
   * expenseTotal/managerLaborCost are real bank-linked figures; when false, they're estimates
   * (manual entries / clocked time). payrollCost/cashProviderCompensation are sourced from the
   * Salary/Commission Report engine (SettlementPreviewService) regardless of this flag. */
  statementCovered: boolean;
  /** The provider's share of cash revenue for this month, sourced from the same engine that
   * drives the Salary/Commission Report — never a fake bank transaction. Null when unknown. */
  cashProviderCompensation: number | null;
  /** "Personal Bank Transactions" — categorized bank transactions in a personal-flagged expense
   * category. Reported separately; never subtracted from netRevenue. Null when unknown. */
  personalBankTotal: number | null;
  /** "Owner Draws" — bank transactions excluded as owner-contribution/cash-withdrawal. Reported
   * separately; never subtracted from netRevenue. Null when unknown. */
  ownerDrawsTotal: number | null;
  /** netRevenue - personalBankTotal - ownerDrawsTotal — a secondary "what's left after the
   * owner's own money movements" figure. Null if any input is null. */
  profitAfterPersonal: number | null;
  /** "Other Cash Business Expenses" — manually-entered generic-category expenses flagged
   * paid-in-cash. Already subtracted into netRevenue; broken out here as its own P&L line. */
  cashBusinessExpenseTotal: number | null;
  /** Category-by-category breakdown of expenseTotal + cashBusinessExpenseTotal combined, keyed by
   * expense category code (e.g. "MATERIALS"). Provider compensation/manager time aren't
   * categories in this ledger, so they never appear here. Null when unknown; an empty object
   * means genuinely zero categorized spend. */
  categoryBreakdown: Record<string, number> | null;
  /** Category-by-category breakdown of personalBankTotal, keyed by expense category code — the
   * owner can flag more than one category personal, so this can genuinely have more than one row.
   * Null when unknown (same conditions as personalBankTotal); an empty object means genuinely
   * zero personal spend. */
  personalBreakdown: Record<string, number> | null;
  /** The bank account's own printed opening/closing balance for this calendar month — real cash
   * movement, best-effort extracted from the statement CSV at upload time. Deliberately does NOT
   * reconcile against netRevenue (provider payroll is paid out whenever the Zelle transfer lands,
   * not necessarily within the calendar month the commission was earned) — a distinct "did the
   * account grow or shrink" signal, not a decomposition of Net. Null when no completed statement
   * overlapping this month captured a balance. */
  bankOpeningBalance: number | null;
  bankClosingBalance: number | null;
}

export interface ProviderYtd {
  providerId: number;
  name: string;
  ytdGross: number;
  ytdPayroll: number;
  ytdPayrollPct: number | null;
}

export interface YearTotals {
  totalGross: number;
  totalCard: number;
  totalCash: number;
}

export interface OwnerOverviewData {
  fromYear: number;
  fromMonth: number;
  toYear: number;
  toMonth: number;
  months: MonthSummary[];
  providers: ProviderYtd[];
  prevYear: YearTotals | null;
  /** When this response was actually computed (ISO instant) — the backend caches this dashboard
   * for 30 days (see docs/CACHING.md), so this is the real last-Square-pull time for the requested
   * range, not the render time. Drives the SyncBadge on the Overview page. */
  syncedAt: string;
}

// --- Marketing dashboard (owner-only, com.salonreview.web.MarketingDashboardController) ---
// Read-only view of the separate salonLandings service's landing-page experiment data.

export interface MarketingVariantStat {
  variantId: string;
  name: string;
  weight: number;
  pageViews: number;
  /** Genuine conversions — distinct real customers who completed the tracked booking flow on
   * this page/variant, each counted once even if they (or a manager, or anyone else) later
   * rebooked them again through the same flow. */
  conversions: number;
  /** Contacts (leads) captured under this variant, matched by name — a later rename won't
   * reattach older contacts to the new name (see the backend DTO's field doc). */
  contactsCreated: number;
  /** Clicks on anything that opens the booking form (step 1) — mani and akluxnails-home both
   * fire this from their one shared "open the booking modal" call site. */
  bookNowClicks: number;
  conversionRate: number;
  /** Distinct real customers Square knows about that conversions doesn't — a manager followed up
   * on a lead and booked them by phone, or the lead's original tracked request was cancelled and
   * a different booking replaced it. Zero unless a manager follow-up has actually been found via
   * the same phone-resolution "Sync" mechanism as the Contacts tab. */
  followUpBookings: number;
  /** (conversions + followUpBookings) / pageViews. Equal to conversionRate when
   * followUpBookings is zero. */
  adjustedConversionRate: number;
  /** Direct ?v=<key> link to view this exact variant, or null if it has no key yet. */
  deepLinkUrl: string | null;
  /** What this variant is testing and why, e.g. "urgency-focused headline + green accent vs.
   * control's neutral tone" — free text, null if never set. */
  description: string | null;
}

export interface MarketingDashboardData {
  available: boolean;
  landingPageSlug: string;
  variants: MarketingVariantStat[];
  /** ISO-8601 instant, or null if no cutoff is set — stats reflect activity from this point
   * forward only, letting an owner exclude their own test traffic from ad-evaluation numbers. */
  statsSince: string | null;
}

// --- Booking-funnel dashboard (com.salonreview.web.FunnelAnalyticsController) ---
// Read-only view of marketing.funnel_events, written by both mani and akluxnails-home's own
// backends. A landing page normally has exactly one flowKey; the endpoint returns an array so a
// flow redesign (a new flowKey) shows as its own funnel instead of a nonsensical merge.

export interface FunnelStepStat {
  stepKey: string;
  stepIndex: number;
  stepCountTotal: number;
  reachedCount: number;
  /** reachedCount / totalStarted, 0 when totalStarted is 0. */
  reachedPctOfStarted: number;
  /** How many sessions reached the previous step (or totalStarted, for the first step) but not this one. */
  dropOffCount: number;
  /** dropOffCount / the previous step's reachedCount (or totalStarted for the first step). */
  dropOffPct: number;
}

export interface FunnelDashboardData {
  landingPageSlug: string;
  variantId: string;
  /** marketing.landing_variants.name — same label shown in the variant list elsewhere in the
   * marketing dashboard. */
  variantName: string;
  /** marketing.landing_variants.key — the ?v= deep-link key, or null if this variant has none
   * (random-pool-only, no direct campaign link). */
  variantKey: string | null;
  /** marketing.landing_variants.weight — 0 means excluded from the random A/B pool (still
   * reachable via variantKey's deep link, if it has one). */
  variantWeight: number;
  /** marketing.landing_variants.active — false means the variant itself has been deactivated
   * outright, distinct from weight=0 (which just excludes it from the random pool). */
  variantEnabled: boolean;
  /** Which booking-flow shape this variant uses — descriptive only; more than one variant can
   * share the same flowKey and still get its own separate row here. */
  flowKey: string;
  /** This variant's own page_view count for the selected period/sources. */
  totalVisitors: number;
  /** Distinct sessions reaching this variant's flow's first step. */
  totalStarted: number;
  steps: FunnelStepStat[];
  /** Sourced from marketing.attribution (Square-reconciled), filtered to this variant — same
   * source as Overview's "Bookings" column, no longer pooled across variants sharing a flow. */
  totalCompleted: number;
  /** totalCompleted / totalVisitors, 0 when totalVisitors is 0. */
  finalConversionRate: number;
  /** True when this variant has recorded activity in the last 7 days — i.e. it's still actually
   * receiving traffic. False means this variant's weight has been zeroed or it's been
   * deactivated a while ago; the data itself is never deleted, it's just no longer part of the
   * live experiment. */
  active: boolean;
  /** ISO timestamp of this variant's most recent recorded funnel event, or null if somehow none exists. */
  lastActivityAt: string | null;
}

// --- AI funnel analysis (com.salonreview.web.FunnelAnalysisController) — owner-only ---
// "Analyze Funnel" button on the Funnel tab. Mirrors the AI triage feature's shape: feature-
// flagged (404 when ai.funnel-analysis.enabled=false), structured Claude output, cached by a
// snapshot of the exact funnel numbers analyzed so a repeat click with unchanged data is free.

export type ImpactLevel = 'HIGH' | 'MEDIUM' | 'LOW';

export interface PrioritizedRecommendation {
  title: string;
  rationale: string;
  expectedImpact: ImpactLevel;
}

export interface FunnelAnalysisResult {
  biggestBottleneckStep: string;
  bottleneckExplanation: string;
  recommendations: PrioritizedRecommendation[];
  suspiciousPatterns: string[];
  suggestedAbTests: string[];
  topPriorityAction: string;
  promptVersion: string;
  model: string;
  /** ISO instant string — when this analysis was generated. */
  createdAt: string;
}

// --- SEO AI Advisor (com.salonreview.web.SeoAiAdvisorController) — owner-only ---
// "Analyze SEO" button on the SEO tab. Same shape as the funnel analysis feature above: feature-
// flagged (404 when ai.seo-advisor.enabled=false), structured Claude output, cached by a snapshot
// of the exact SEO numbers analyzed so a repeat click with unchanged data is free.

export type SeoOverallStatus = 'HEALTHY' | 'NEEDS_ATTENTION' | 'CRITICAL';

export interface SeoRecommendation {
  priority: number;
  action: string;
  why: string;
  evidence: string;
  expectedImpact: ImpactLevel;
  effort: ImpactLevel;
  confidence: ImpactLevel;
  suggestedImplementation: string;
  relevantPageOrKeyword: string | null;
}

export interface SeoAnalysisResult {
  overallStatus: SeoOverallStatus;
  executiveSummary: string;
  wins: string[];
  problems: string[];
  recommendations: SeoRecommendation[];
  promptVersion: string;
  model: string;
  /** ISO instant string — when this analysis was generated. */
  createdAt: string;
}

/** One row of marketing.landing_pages — feeds the Overview tab's page selector. */
export interface MarketingLandingPage {
  slug: string;
  name: string;
}

// --- Marketing analytics (com.salonreview.web.MarketingAnalyticsController) ---

export interface MarketingAnalyticsSegment {
  /** Distinct ads-attributed (Meta/Google paid click) customers with a service in range. */
  customerCount: number;
  /** Individual service line items — a mani+pedi visit counts as 2. */
  serviceCount: number;
  /** Sum of menu-price ("gross") revenue for those services. */
  grossRevenue: number;
}

export interface MarketingUpcomingAppointment {
  customerId: string;
  customerName: string;
  /** Multi-service visits are joined with " + ", e.g. "Manicure + Pedicure". */
  serviceName: string;
  /** ISO-8601 instant. */
  startAt: string;
  /** When this reservation was actually made (Square booking's own created_at) — what period
   * bucketing (Ads Report weeks/months, and this ledger's own in-period/outside-period split)
   * keys on, so a booking made late in one period for a visit landing in the next is still
   * counted in the period it was actually booked in. Falls back to startAt when unresolvable. */
  bookedAt: string;
  /** Summed catalog list price across the visit's service(s). */
  price: number;
  /** Whether Square's record for this customer was created fresh off this ad touch. */
  freshFromAds: boolean;
  /** Whether this customer's own firstTouch fell within the requested [from, to] — the cohort the
   * Ads Report's "Anticipated (outside period)" figure is restricted to. Every ads customer's
   * upcoming appointments are included in this list (not just this cohort); use this flag to split
   * it into "this period" (startAt within [from, to], any customer) vs. "outside period" (startAt
   * outside it, but only where this is true) and match those headline figures exactly. */
  capturedInRange: boolean;
  /** The real Square booking id — use this (not customerId+startAt) as a row's unique key; two
   * different bookings could in principle share a startAt. */
  bookingId: string | null;
}

export interface MarketingCompletedAppointment {
  customerId: string;
  customerName: string;
  /** Multi-service visits are joined with " + ", e.g. "Manicure + Pedicure". */
  serviceName: string;
  /** ISO-8601 date (yyyy-MM-dd). */
  date: string;
  /** What was actually collected (after any discount) — a real amount, not a catalog estimate. */
  collected: number;
  /** "CASH" (checked out as cash in Square), "CARD", or "CASH-NOTE" (a provider's note, no Square
   * checkout) — the same classification used for payroll. */
  paymentChannel: 'CASH' | 'CARD' | 'CASH-NOTE';
  freshFromAds: boolean;
  /** The real Square booking id — use this (not customerId+date+serviceName) as a row's unique
   * key. Two genuinely different same-day cash-note appointments for one customer both get the
   * identical generic serviceName "cash note (N counted)", so date+serviceName alone can collide
   * (seen in production: caused a React duplicate-key bug in the Ads Report ledger). */
  bookingId: string | null;
}

export interface MarketingCancelledAppointment {
  customerId: string;
  customerName: string;
  /** Multi-service visits are joined with " + ", e.g. "Manicure + Pedicure". */
  serviceName: string;
  /** ISO-8601 date (yyyy-MM-dd) — the booking's own start date, any date past or future relative
   * to today (a booking can be cancelled ahead of its own date). */
  date: string;
  /** Same reasoning as MarketingUpcomingAppointment.bookedAt — what period bucketing keys on
   * instead of `date` (the cancelled visit's own date). ISO-8601 date (yyyy-MM-dd). */
  bookedDate: string;
  /** Catalog list-price estimate — there's nothing actually collected to report here. */
  price: number;
  /** Square's own raw booking status. */
  status: 'CANCELLED_BY_CUSTOMER' | 'CANCELLED_BY_SELLER' | 'DECLINED' | 'NO_SHOW';
  freshFromAds: boolean;
  /** Same meaning as MarketingUpcomingAppointment.capturedInRange. */
  capturedInRange: boolean;
  /** Same reasoning as MarketingCompletedAppointment.bookingId. */
  bookingId: string | null;
}

export interface MarketingAnalyticsData {
  /** ISO-8601 date (yyyy-MM-dd), inclusive on both ends. */
  from: string;
  to: string;
  /** Every ads-attributed customer with a service in range. */
  all: MarketingAnalyticsSegment;
  /** Only customers whose Square record was created fresh off the ad touch — a genuinely new customer. */
  fresh: MarketingAnalyticsSegment;
  /** Only customers who already existed in Square before coming back through an ad. */
  returning: MarketingAnalyticsSegment;
  /** Every still-upcoming appointment for an ads-attributed customer, regardless of [from, to]. */
  upcoming: MarketingUpcomingAppointment[];
  /** Every already-completed, actually-paid appointment within [from, to] — one row per booking,
   * with the real collected amount and payment channel. Comps are excluded (nothing collected). */
  completed: MarketingCompletedAppointment[];
  /** Every real booking for an ads-attributed customer that didn't happen (cancelled by either
   * side, declined, or no-show) — any date, regardless of [from, to]. */
  cancelled: MarketingCancelledAppointment[];
  /** Gross revenue for every ads customer, fixed to [1st of the current month, today] — independent
   * of [from, to], so the ROI card always means "this month". */
  currentMonthToDate: MarketingAnalyticsSegment;
  /** Manually-entered ad spend for the current calendar month; zero if never entered. */
  adSpendThisMonth: number;
}

// --- Marketing Ads Report (com.salonreview.web.MarketingAdsReportController) ---

/** A money figure split by whether it came from a customer's first ads-attributed visit vs. a
 * later, repeat one — firstVisit + repeat always equals the un-split figure it accompanies. */
export interface MoneySplit {
  firstVisit: number;
  repeat: number;
}

/** Same split as MoneySplit, for a headline count instead of a dollar figure. */
export interface CountSplit {
  firstVisit: number;
  repeat: number;
}

export interface MarketingAdsReportPeriod {
  /** ISO-8601 dates (yyyy-MM-dd), inclusive on both ends. */
  periodStart: string;
  periodEnd: string;
  /** Resolved from the flexible per-page `ad_spend_entries` ledger (see AdSpendResolver) —
   * prorated by calendar-day overlap when entries don't exactly tile the period. */
  adSpend: number;
  /** True when adSpend needed any proration (a gap, an overlap, or a clipped entry) — false only
   * when entries exactly, non-overlappingly tile the period. */
  adSpendEstimated: boolean;
  /** What was actually collected (cash/card/cash-note) for ads-attributed appointments completed
   * in this period — real, not a catalog estimate. Comps excluded (nothing collected). Includes
   * manager-follow-up appointments (see customersFollowedUp). */
  revenueCollected: number;
  /** revenueCollected split by first-visit vs. repeat. */
  revenueCollectedSplit: MoneySplit;
  /** Catalog-price value of still-upcoming ads-attributed appointments scheduled in this period —
   * zero for periods entirely in the past. Includes not-yet-paid follow-up appointments. */
  anticipatedRevenue: number;
  /** anticipatedRevenue split by first-visit vs. repeat. */
  anticipatedRevenueSplit: MoneySplit;
  /** Ads-attributed customers whose Square record was created fresh off the ad touch, with a
   * service rendered in this period — booked through the tracked flow itself, not a manager
   * follow-up (see customersFollowedUp). */
  customersCreated: number;
  /** Catalog-price value of upcoming appointments dated outside this row's own period, booked by
   * exactly the customers whose own firstTouch falls within this period — "of the leads this
   * specific window brought in, what have they got booked beyond it". Not every ads customer ever:
   * that would make this figure identical for every past period, since a past period's own dates
   * can never contain a future appointment regardless of whose it is. revenueCollected +
   * anticipatedRevenue + this = what the WhatsApp text export calls "Total". */
  anticipatedRevenueOutsidePeriod: number;
  /** anticipatedRevenueOutsidePeriod split by first-visit vs. repeat. */
  anticipatedRevenueOutsidePeriodSplit: MoneySplit;
  /** Count of distinct completed, actually-paid appointments (not service line items) in this period. */
  completedAppointments: number;
  /** completedAppointments split by first-visit vs. repeat. */
  completedAppointmentsSplit: CountSplit;
  /** Real bookings for ads-attributed customers, dated within this period, that didn't happen
   * (cancelled by customer/seller, declined, or no-show). completedAppointments + this +
   * anticipatedAppointments is the full bookings breakdown for this period. */
  cancelledBookings: number;
  /** Count of still-upcoming appointments scheduled within this period — the same appointments
   * anticipatedRevenue above sums the price of, just the headline count.
   * completedAppointments + cancelledBookings + anticipatedAppointments +
   * anticipatedAppointmentsOutsidePeriod is the full bookings breakdown for this period. */
  anticipatedAppointments: number;
  /** anticipatedAppointments split by first-visit vs. repeat. */
  anticipatedAppointmentsSplit: CountSplit;
  /** Count of still-upcoming appointments dated outside this row's own period, booked by exactly
   * the customers captured (firstTouch) within that same window — the headline count for
   * anticipatedRevenueOutsidePeriod, same scoping and same reasoning. */
  anticipatedAppointmentsOutsidePeriod: number;
  /** anticipatedAppointmentsOutsidePeriod split by first-visit vs. repeat. */
  anticipatedAppointmentsOutsidePeriodSplit: CountSplit;
  /** Real, non-cancelled Square appointments in this period for this page's ads-attributed
   * contacts that the tracked flow never recorded — a lead a manager booked by phone after the
   * on-site flow didn't complete. Already folded into revenueCollected/anticipatedRevenue above;
   * this is just the headline count. */
  customersFollowedUp: number;
  /** True when this row's periodEnd is still in the future relative to today — a Full Month
   * report viewed before the month closes. Always false for WEEK/MONTH_TO_DATE/CUSTOM rows that
   * don't extend past today. */
  monthInProgress: boolean;
  /** Distinct customers behind completedAppointments — a customer with two completed visits in
   * the same period counts once here but twice there. Answers "how many people" alongside "how
   * many bookings", for each bucket the Customers block below draws the same distinction for. */
  customersCollected: number;
  /** Distinct customers behind cancelledBookings. */
  customersCancelled: number;
  /** Distinct customers behind anticipatedAppointments. */
  customersAnticipated: number;
  /** Distinct customers behind anticipatedAppointmentsOutsidePeriod — same captured-in-this-window
   * scoping (see anticipatedAppointmentsOutsidePeriod's own doc). */
  customersAnticipatedOutsidePeriod: number;
}

export interface MarketingAdsReportData {
  /** Which grain `periods` is bucketed into. WEEK/MONTH may return several historical rows (a
   * trend); MONTH_TO_DATE, CUSTOM, and ALL always return exactly one. */
  periodType: 'WEEK' | 'MONTH' | 'MONTH_TO_DATE' | 'CUSTOM' | 'ALL';
  /** One row per period, most recent first. */
  periods: MarketingAdsReportPeriod[];
  /** Sum (or, for adSpendEstimated/monthInProgress, OR) across every row in `periods` — the
   * report's grand-total row. */
  totals: MarketingAdsReportPeriod;
}

/** All-time customer lifetime value by acquisition channel, for one landing page — pairs with the
 * Ads Report's per-period cost figures to answer "which channel's customers are actually worth it
 * long-term", not just "which channel books the cheapest first visit". Never period-bucketed: a
 * customer's LTV is their total revenue collected across every visit since their first touch. */
export interface ChannelLtv {
  /** One of the five TrafficSourceKey values, or "all" (the totals row), or "other" (a contact
   * whose channel didn't classify into any of the five). */
  channel: string;
  /** Distinct customers ever attributed to this channel — the LTV denominator, including a
   * customer who never actually paid anything (e.g. cancelled their only booking), at $0. */
  customerCount: number;
  /** All-time gross revenue collected (any visit, any date) from exactly these customers. */
  totalRevenue: number;
  /** totalRevenue / customerCount — null when customerCount is 0. */
  averageLtv: number | null;
}

export interface MarketingLtvData {
  /** One row per recognized channel, always present even at zero customers, plus "other" only
   * when at least one customer's channel didn't classify into any of the five. */
  channels: ChannelLtv[];
  /** Every channel combined, same no-double-counting guarantee as the Ads Report's totals row. */
  totals: ChannelLtv;
}

/** One Square customer's submission + appointment history, fetched lazily when a row on the Ads
 * Report breakdown drill-down is expanded (see MarketingAdsReportController#customerHistory) —
 * same shape as MarketingContact's own fields, just reachable by Square customer id instead of by
 * contact row. */
export interface MarketingCustomerHistory {
  submissions: MarketingContactSubmission[];
  appointments: MarketingContactAppointment[];
}

// --- Ad spend entries (com.salonreview.web.MarketingAdsReportController) ---

export interface AdSpendEntry {
  id: number;
  landingPageSlug: string;
  /** ISO-8601 dates (yyyy-MM-dd), inclusive on both ends. */
  periodStart: string;
  periodEnd: string;
  amount: number;
  enteredBy: string | null;
  /** ISO-8601 instant. */
  enteredAt: string;
}

// --- Expense entries (com.salonreview.web.ExpenseController) ---

/** A category's `code` (see ExpenseCategoryDefinition below) — owner-editable, so no longer a
 * closed set at the type level; MATERIALS/RENT/UTILITIES/OTHER/MANAGER_TIME/PROVIDER_PAYROLL are
 * just the seeded defaults, not the only valid values. */
export type ExpenseCategory = string;

/** Owner-editable expense category (com.salonreview.web.ExpenseCategoryController). `code` is
 * the stable value stored on ExpenseEntry/BankTransaction/MerchantRule and never changes after
 * creation; `label` is the display name and can be freely renamed. `locked` categories
 * (MANAGER_TIME, PROVIDER_PAYROLL) carry hardcoded backend behavior and can't be deleted. */
export interface ExpenseCategoryDefinition {
  id: number;
  code: string;
  label: string;
  locked: boolean;
  sortOrder: number;
  /** Personal (non-business) spend — excluded from Net Profit, reported separately on the P&L. */
  isPersonal: boolean;
}

export interface ExpenseEntry {
  id: number;
  category: ExpenseCategory;
  /** ISO-8601 dates (yyyy-MM-dd), inclusive on both ends. */
  periodStart: string;
  periodEnd: string;
  amount: number;
  note: string | null;
  enteredBy: string | null;
  /** ISO-8601 instant. */
  enteredAt: string;
  /** Manual entries only — reconciliation-derived entries are always bank-sourced. */
  paidInCash: boolean;
}

// --- Bank statement import + reconciliation (com.salonreview.web.ExpenseImportController /
// MerchantRuleController) — openspec change expense-import-reconciliation ---

export type BankStatementImportStatus = 'AWAITING_REVIEW' | 'COMPLETED' | 'REVERTED';

export interface BankStatementImportSummary {
  id: number;
  originalFilename: string;
  rowCount: number;
  /** ISO-8601 dates (yyyy-MM-dd), or null for an import with no parsed rows. */
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  status: BankStatementImportStatus;
  uploadedBy: string | null;
  /** ISO-8601 instants. */
  uploadedAt: string;
  completedAt: string | null;
  revertedAt: string | null;
  /** The statement's own printed opening/closing balance, best-effort extracted from the CSV —
   * null for imports uploaded before this was captured, or when the export doesn't include it.
   * Powers the reconciliation workspace's "Opening → Closing" bank-account check. */
  openingBalance: number | null;
  closingBalance: number | null;
}

export type BankTransactionStatus =
  | 'UNMATCHED' | 'AUTO_MATCHED' | 'NEEDS_REVIEW' | 'REVIEWED' | 'EXCLUDED' | 'DUPLICATE';

export type ExcludeReason =
  | 'TRANSFER' | 'CREDIT_CARD_PAYMENT' | 'PAYROLL' | 'TAX' | 'OWNER_CONTRIBUTION'
  | 'CASH_WITHDRAWAL' | 'REFUND' | 'OTHER' | 'DEPOSIT';

export interface BankTransaction {
  id: number;
  importId: number;
  /** ISO-8601 date (yyyy-MM-dd). */
  transactionDate: string;
  rawDescription: string;
  normalizedMerchant: string;
  /** Signed; negative = money out. */
  amount: number;
  status: BankTransactionStatus;
  matchReason: string | null;
  /** 0.00-1.00, null when Unknown. */
  confidence: number | null;
  /** An ExpenseCategory value, set once AUTO_MATCHED or REVIEWED (and not excluded). */
  category: ExpenseCategory | null;
  excludedReason: ExcludeReason | null;
  linkedExpenseEntryId: number | null;
  duplicateOfTransactionId: number | null;
  reviewedBy: string | null;
  /** ISO-8601 instant. */
  reviewedAt: string | null;
}

export interface BankStatementImportDetail {
  importSummary: BankStatementImportSummary;
  transactions: BankTransaction[];
}

export type MerchantRuleType = 'FINGERPRINT' | 'MERCHANT' | 'MERCHANT_KEYWORD' | 'MERCHANT_AMOUNT_RANGE' | 'KEYWORD';

export interface MerchantRule {
  id: number;
  ruleType: MerchantRuleType;
  /** Null only for KEYWORD (merchant-agnostic) rules. */
  normalizedMerchant: string | null;
  /** MERCHANT_KEYWORD: a single substring. KEYWORD: one or more required substrings joined by
   * "\n" (all must be present in the description — AND semantics). */
  keyword: string | null;
  amountMin: number | null;
  amountMax: number | null;
  /** An ExpenseCategory value, or an `EXCLUDE_<reason>` pseudo-value (see ExcludeReason). */
  category: string;
  active: boolean;
  createdBy: string | null;
  /** ISO-8601 instants. */
  createdAt: string;
  updatedAt: string;
  timesApplied: number;
  lastAppliedAt: string | null;
}

// Shared across the Overview, Contacts, Analytics, and Funnel tabs — see backend TrafficSourceSql.
export type TrafficSourceKey = 'meta_ads' | 'google_ads' | 'instagram_organic' | 'google_organic' | 'direct';

// --- Abuse blocks (com.salonreview.web.AbuseBlocksController) ---

export interface AbuseBlockEntry {
  endpoint: string;
  reason: string;
  phoneNumber: string | null;
  ipAddress: string | null;
  occurredAt: string;
}

export interface AbuseBlocksData {
  available: boolean;
  /** Count of rejected submissions in the last 24h, grouped by reason. */
  countsByReasonLast24h: Record<string, number>;
  recent: AbuseBlockEntry[];
}

// --- Marketing contacts (com.salonreview.web.MarketingContactsController) ---

export interface MarketingContact {
  id: string;
  givenName: string | null;
  /** Last name — Square-resolved (marketing.contacts itself has no family_name column), best-
   * effort only when a Square customer is already linked. Null otherwise. */
  familyName: string | null;
  phoneNumber: string;
  emailAddress: string | null;
  originalTrafficSource: string | null;
  marketingTrafficSource: string | null;
  /** One of the five TrafficSourceKey buckets, computed server-side — null for the rare edge
   * case that fits none of them. Prefer this over originalTrafficSource/marketingTrafficSource
   * for filtering: those are salonLandings' own labels, which can mislabel an organic Instagram
   * bio-link/post click as "Meta Ads" (see backend TrafficSourceSql). */
  channel: TrafficSourceKey | null;
  /** Latest touch's raw UTM — like marketingTrafficSource, overwritten on every capture event,
   * not preserved as first-touch. */
  utmSource: string | null;
  utmMedium: string | null;
  utmCampaign: string | null;
  /** Landing page + variant the lead first saw — denormalized at capture time, so a later
   * rename/delete of the variant never changes what this record says. */
  landingPageSlug: string | null;
  variantName: string | null;
  /** Most recent visit's device/OS/browser. */
  deviceType: string | null;
  osName: string | null;
  osVersion: string | null;
  browserName: string | null;
  browserVersion: string | null;
  smsMarketingConsent: boolean | null;
  emailMarketingConsent: boolean | null;
  /** Square Dashboard customer profile link, or null if no Square customer is known for this
   * contact yet (neither found by lookup nor created by a booking). */
  squareProfileUrl: string | null;
  /** Every form submission this contact's phone/email ever made, most recent first. Always
   * populated (cheap — our own DB) so the UI can show "no submissions" without an extra click,
   * though in practice every contact has at least one. */
  submissions: MarketingContactSubmission[];
  /** This contact's Square appointment history, most recent/upcoming first. Empty (never null)
   * when no Square customer is known, or Square has no bookings for them — the UI doesn't need
   * to distinguish those two cases. */
  appointments: MarketingContactAppointment[];
  createdAt: string;
  updatedAt: string;
  /** Most recent time this contact was sent / actually clicked the checkout-review automation's
   * Google-review link — both null if never sent. clickedAt null with sentAt set means "sent, but
   * hasn't clicked yet", distinct from "never asked" (see ContactInfoPanel's review-links section). */
  googleReviewSentAt: string | null;
  googleReviewClickedAt: string | null;
  /** Same pair for the checkout-review automation's middle escalation rung — a Yelp-review ask,
   * sent only to a contact who already left a Google review (see backend CheckoutReviewLinks'
   * 3-rung Google -> Yelp -> feedback-form escalation). */
  yelpReviewSentAt: string | null;
  yelpReviewClickedAt: string | null;
  /** Same pair for the private feedback-form link (negative branch, or a repeat reviewer's
   * positive branch — see backend CheckoutReviewLinks). */
  feedbackFormSentAt: string | null;
  feedbackFormClickedAt: string | null;
  /** True once this Square customer's distinct-day visit count reaches the configured VIP
   * threshold — strictly data-driven, no manual override (see backend
   * MarketingContactsService#visitCountsByCustomerId). Always false when no Square customer is
   * known yet. */
  vip: boolean;
  /** The distinct-day visit count backing `vip`, or null when no Square customer is known. */
  visitCount: number | null;
}

export interface MarketingContactsData {
  available: boolean;
  contacts: MarketingContact[];
}

/** Response shape for POST /api/owner/marketing/contacts/enrich — the lazy follow-up for a
 * MarketingContact's familyName/appointments, deliberately absent from the bulk contacts list
 * (see MarketingContact's own field docs). */
export interface MarketingContactEnrichment {
  familyName: string | null;
  appointments: MarketingContactAppointment[];
}

export interface MarketingSyncStatusData {
  /** ISO-8601 instant "Sync appointments" was last actually run; null if never. */
  lastSyncedAt: string | null;
}

export interface MarketingContactSubmission {
  submissionType: string;
  occurredAt: string;
  landingPageSlug: string | null;
  variantName: string | null;
  /** Same classification shown for a contact's own traffic source (e.g. "Direct / No referrer",
   * "google / cpc / promo") — never blank for a submission recorded after this field existed;
   * null only on older rows. */
  trafficSource: string | null;
  utmSource: string | null;
  utmMedium: string | null;
  utmCampaign: string | null;
  serviceName: string | null;
  price: number | null;
}

export interface MarketingContactAppointment {
  bookingId: string;
  status: string;
  startAt: string | null;
  serviceName: string | null;
  /** Current catalog list price — Square doesn't retain what was actually charged, so this is a
   * best-effort estimate, not a payroll figure. */
  price: number | null;
  artistName: string | null;
  /** How this appointment was actually paid — "CASH" (checked out as cash in Square), "CARD", or
   * "CASH-NOTE" (a provider's cash note, no Square checkout). Null if this appointment isn't tied
   * to a matched payment: still upcoming, cancelled/no-show/declined, or a past visit with no
   * matching order or cash note found. */
  paymentChannel: 'CASH' | 'CARD' | 'CASH-NOTE' | null;
  /** What was actually collected for this appointment (after any discount) — unlike price above,
   * this is the real amount, not a catalog estimate. Null under the same conditions as paymentChannel. */
  collectedAmount: number | null;
  /** From the marketing.submissions row that actually created this booking (matched by
   * square_booking_id) — all null if this appointment didn't originate through our own booking
   * funnel (e.g. booked in person, or through Square directly, before we ever tracked them). */
  trafficSource: string | null;
  deviceType: string | null;
  osName: string | null;
  osVersion: string | null;
  browserName: string | null;
  submissionOccurredAt: string | null;
}

// --- Knowledge Base articles (com.salonreview.web.KbArticleController) ---

export type KbSyncStatus = 'NOT_SYNCED' | 'SYNCED' | 'CHANGED' | 'ERROR';

// A SOP's RAG-sync state for the assistant-admin section (com.salonreview.web.SopSyncController).
export interface SopSyncItem {
  id: number;
  title: string;
  category: string;
  syncStatus: KbSyncStatus;
  lastSyncError: string | null;
  published: boolean;
  hasTranslation: boolean;
}

export interface KbArticle {
  id: number;
  title: string;
  /** Russian translation of title; null falls back to title. */
  titleRu: string | null;
  category: string;
  body: string;
  bodyRu: string | null;
  visibleRoles: Role[];
  syncStatus: KbSyncStatus;
  ragDocId: number | null;
  lastSyncedAt: string | null;
  lastSyncedBy: string | null;
  lastSyncError: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface KbWriteRequest {
  title: string;
  titleRu: string | null;
  category: string;
  body: string;
  bodyRu: string | null;
  visibleRoles: Role[];
}

// --- SOPs (com.salonreview.web.SopController) ---

export type SopAudience = 'MANAGER' | 'PROVIDER' | 'BOTH';
export type SopStatus = 'ACTIVE' | 'ARCHIVED';
export type SopVersionStatus = 'DRAFT' | 'PUBLISHED';

export interface SopVersion {
  id: number;
  versionNumber: number;
  body: string;
  bodyRu: string | null;
  changeNote: string | null;
  changeNoteRu: string | null;
  status: SopVersionStatus;
  createdBy: string;
  createdAt: string;
}

export interface Sop {
  id: number;
  title: string;
  /** Russian translation of title; null falls back to title. */
  titleRu: string | null;
  category: string;
  audience: SopAudience;
  status: SopStatus;
  priority: number; // onboarding sort order — lower shows first
  currentVersion: SopVersion | null;
  acknowledged: boolean;
  acknowledgedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface SopRosterEntry {
  userId: number;
  username: string;
  role: Role;
  acknowledged: boolean;
  acknowledgedAt: string | null;
}

export interface SopCreateRequest {
  title: string;
  titleRu: string | null;
  category: string;
  audience: SopAudience;
  priority?: number;
  body: string;
  bodyRu: string | null;
}

export interface SopUpdateRequest {
  title: string;
  titleRu: string | null;
  category: string;
  audience: SopAudience;
  priority?: number;
}

// --- Staff documents (com.salonreview.web.StaffDocumentController) ---

export type StaffDocumentExpirationStatus = 'OK' | 'EXPIRING_SOON' | 'EXPIRED';

export interface StaffDocument {
  id: number;
  /** "PROVIDER" or "MANAGER" — service providers and managers only; owners aren't covered. */
  personType: 'PROVIDER' | 'MANAGER';
  /** providers.id for a provider, app_user.id for a manager. */
  personId: number;
  personName: string;
  /** Freeform (e.g. "Contract", "License", "NDA") — not a fixed set. */
  documentType: string;
  label: string | null;
  fileName: string;
  /** ISO-8601 date (yyyy-MM-dd). */
  expirationDate: string;
  status: StaffDocumentExpirationStatus;
  createdBy: string;
  /** ISO-8601 instant. */
  createdAt: string;
}
