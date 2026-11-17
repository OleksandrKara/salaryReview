export const SMS_MAX_CHARS = 1600;
export const SMS_SEGMENT_CHARS = 160;

export type BodyPart = { text: string; bold: boolean };

// A *bold* pair must sit on one line, wrap non-space text and not touch spaces inside the marks,
// so "5 * 3 * 2", lone asterisks and "* bullet" lines stay literal. Both "*x*" and "**x**" match.
const BOLD_RE = /(\*{1,2})(?=\S)([^*\n]*?\S)\1(?!\*)/g;

export function parseBold(body: string): BodyPart[] {
  const parts: BodyPart[] = [];
  let last = 0;
  for (const m of body.matchAll(BOLD_RE)) {
    const start = m.index ?? 0;
    if (start > last) parts.push({ text: body.slice(last, start), bold: false });
    parts.push({ text: m[2], bold: true });
    last = start + m[0].length;
  }
  if (last < body.length) parts.push({ text: body.slice(last), bold: false });
  return parts;
}

export function hasBoldMarkers(body: string): boolean {
  return parseBold(body).some((p) => p.bold);
}

export function stripBoldMarkers(body: string): string {
  return parseBold(body).map((p) => p.text).join('');
}

export function smsLengthState(body: string): 'ok' | 'long' | 'too_long' {
  if (body.length > SMS_MAX_CHARS) return 'too_long';
  if (body.length > SMS_SEGMENT_CHARS * 4) return 'long';
  return 'ok';
}

const GSM7_BASIC = new Set(Array.from(
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà",
));
const GSM7_EXT = new Set(Array.from('^{}\\[~]|€'));

export type SmsSegmentInfo = {
  encoding: 'GSM-7' | 'UCS-2';
  units: number;
  segments: number;
  perSegment: number;
  offending: string[];
};

// Twilio bills per segment: GSM-7 is 160 (153 when split), UCS-2 is 70 (67 when split). One emoji,
// Cyrillic letter or curly quote switches the whole message to UCS-2.
export function smsSegmentInfo(body: string): SmsSegmentInfo {
  const offending: string[] = [];
  let gsmUnits = 0;
  for (const ch of Array.from(body)) {
    if (GSM7_BASIC.has(ch)) gsmUnits += 1;
    else if (GSM7_EXT.has(ch)) gsmUnits += 2;
    else if (!offending.includes(ch)) offending.push(ch);
  }
  if (offending.length === 0) {
    const segments = gsmUnits === 0 ? 0 : gsmUnits <= 160 ? 1 : Math.ceil(gsmUnits / 153);
    return { encoding: 'GSM-7', units: gsmUnits, segments, perSegment: segments > 1 ? 153 : 160, offending };
  }
  const units = body.length;
  const segments = units === 0 ? 0 : units <= 70 ? 1 : Math.ceil(units / 67);
  return { encoding: 'UCS-2', units, segments, perSegment: segments > 1 ? 67 : 70, offending };
}

export type SmsWarning = { level: 'info' | 'error'; text: string };

// Live warning under the composer. null when nothing needs saying (a single plain segment).
export function smsLengthWarning(body: string): SmsWarning | null {
  const n = body.length;
  if (n > SMS_MAX_CHARS) {
    return {
      level: 'error',
      text: `Too long: ${n} of ${SMS_MAX_CHARS} characters (the Twilio limit). Shorten it by ${n - SMS_MAX_CHARS} characters or split it into two messages. Sending is blocked until it fits.`,
    };
  }
  const info = smsSegmentInfo(body);
  if (info.segments <= 1 && !(info.encoding === 'UCS-2' && n > 0)) return null;
  const shown = info.offending.slice(0, 4).join(' ');
  const enc = info.encoding === 'UCS-2'
    ? ` It contains ${shown}, so it is sent as UCS-2 (${info.perSegment} characters per segment instead of 160). Replacing those characters makes it cheaper.`
    : '';
  if (info.segments <= 1) return { level: 'info', text: `${n} characters, 1 SMS segment.${enc}` };
  return {
    level: 'info',
    text: `${n} of ${SMS_MAX_CHARS} characters = ${info.segments} SMS segments (${info.encoding}, ${info.perSegment} per segment). Each segment is billed separately, so keep it short.${enc}`,
  };
}

export function isOverSmsLimit(body: string): boolean {
  return body.length > SMS_MAX_CHARS;
}

export const SEND_UNKNOWN_MESSAGE =
  'We could not confirm this message was sent (connection problem or no answer). It may have gone through. Check the conversation above before sending again. Your text is still in the box.';

export function sendFailureMessage(reason: string | null | undefined, detail?: string | null): string {
  if (reason === 'empty') return 'Not sent: the message is empty.';
  if (reason === 'invalid_phone') return 'Not sent: this phone number is not valid, so it cannot receive texts.';
  if (reason === 'media_too_large') return 'Not sent: a photo is larger than 5 MB. Use a smaller photo.';
  if (reason === 'not_configured') return 'Not sent: texting is not set up for this business (no sending number). Tell the owner.';
  if (reason === 'send_failed' && detail) return `Not sent: ${detail}. The client did not receive it. Your text is still in the box.`;
  if (reason === 'too_long') return `Not sent: the message is longer than ${SMS_MAX_CHARS} characters. Shorten it or split it into two messages.`;
  if (reason === 'blocked') return 'Not sent: this number is blocked.';
  if (reason === 'duplicate_recent') return 'Not sent: the same text was just sent to this client. Check the thread before sending again.';
  return 'Not sent. Nothing was delivered to the client. Your text is still in the box, so you can try again.';
}
