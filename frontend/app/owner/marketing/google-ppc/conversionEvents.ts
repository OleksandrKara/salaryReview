/**
 * The conversions of the Google PPC tab and the event that fires each one. The event is the GA4 event name Google Ads imports as a
 * conversion action ("AnnaKara GA4 (web) sms_click" in Google Ads), so every number here can be checked against the Ads and
 * Analytics screens.
 */
export type ConvKey = 'sms' | 'calls' | 'ringostat' | 'consultations' | 'deposits' | 'forms';

export interface ConversionEvent {
  key: ConvKey;
  label: string;
  /** GA4 event / Google Ads conversion action name. */
  event: string;
  /** What the visitor did, in one line. */
  what: string;
}

export const CONVERSION_EVENTS: ConversionEvent[] = [
  { key: 'sms', label: 'Text taps', event: 'sms_click', what: 'Tap on a Text us button on the site' },
  { key: 'calls', label: 'Call taps', event: 'phoneNumber_click', what: 'Tap on a phone number on the site' },
  { key: 'ringostat', label: 'Phone calls', event: 'Ringostat_calls', what: 'A real call from a Google ad, received by the call tracker (Ringostat)' },
  { key: 'consultations', label: 'Consultations booked', event: 'consultation_booked', what: 'A consultation booked on the site' },
  { key: 'deposits', label: 'Deposits from the site', event: 'procedure_booked', what: 'A deposit paid on the site (Book now, Book a procedure). Invoices do not count' },
  { key: 'forms', label: 'Academy forms', event: 'academy_request', what: 'The Academy course request form, repeats not counted' },
];

