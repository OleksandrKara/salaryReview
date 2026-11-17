import type { Language } from './types';

export type IntakeStatus = 'PENDING' | 'SENT' | 'SIGNED';

export interface IntakeSummary {
  id: number;
  firstName: string | null;
  phoneLast4: string;
  appointmentStartAt: string;
  status: IntakeStatus;
  sentAt: string | null;
  signedAt: string | null;
  signedVia: 'CLIENT_FORM' | 'STAFF_PAPER' | null;
  needsAttention: boolean;
  providerName: string | null;
  initialSmsState: string | null;
  reminderSmsState: string | null;
  openedAt: string | null;
}

export interface IntakeDetail {
  summary: IntakeSummary;
  signatureName: string | null;
  answers: { key: string; label: string; value: string }[];
}

export interface IntakePreviewRow {
  firstInitial: string;
  phoneLast4: string;
  appointmentStartAt: string;
  verdict: string;
}

const PT = 'America/Los_Angeles';

export function intakeWhen(iso: string, lang: Language | null): string {
  return new Date(iso).toLocaleString(lang === 'RU' ? 'ru-RU' : 'en-US', {
    weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: PT,
  });
}

const DICT: Record<string, { EN: string; RU: string }> = {
  title: { EN: 'Intake forms', RU: 'Анкеты новых клиентов' },
  badgeSigned: { EN: 'Form signed', RU: 'Анкета подписана' },
  badgePaper: { EN: 'Form on paper', RU: 'Анкета на бумаге' },
  badgeSent: { EN: 'Form not signed', RU: 'Анкета не подписана' },
  badgePending: { EN: 'Form queued', RU: 'Анкета в очереди' },
  attention: { EN: 'Check before service', RU: 'Проверить до услуги' },
  showAnswers: { EN: 'Show answers', RU: 'Показать ответы' },
  hideAnswers: { EN: 'Hide answers', RU: 'Скрыть ответы' },
  resend: { EN: 'Resend form', RU: 'Отправить анкету снова' },
  resendBody: { EN: 'The standard text with the personal form link will be sent to this client now.', RU: 'Клиенту сейчас уйдёт стандартное сообщение со ссылкой на анкету.' },
  markPaper: { EN: 'Received on paper', RU: 'Получена на бумаге' },
  signedBy: { EN: 'Signed as', RU: 'Подпись' },
  opened: { EN: 'Opened', RU: 'Открыта' },
  notOpened: { EN: 'Not opened yet', RU: 'Ещё не открывали' },
  visit: { EN: 'Visit', RU: 'Визит' },
  technician: { EN: 'Technician', RU: 'Мастер' },
  none: { EN: 'No intake forms for upcoming first visits.', RU: 'Нет анкет для ближайших первых визитов.' },
  loading: { EN: 'Loading', RU: 'Загрузка' },
  failed: { EN: 'Could not load the form status.', RU: 'Не удалось загрузить статус анкеты.' },
  resendDone: { EN: 'Sent.', RU: 'Отправлено.' },
  resendTooSoon: { EN: 'Already resent a few minutes ago.', RU: 'Уже отправляли несколько минут назад.' },
  resendSigned: { EN: 'The form is already signed.', RU: 'Анкета уже подписана.' },
  resendNotSent: { EN: 'The text was not sent (client opted out, quiet hours or SMS is off).', RU: 'Сообщение не ушло (клиент отписался или SMS выключены).' },
  resendError: { EN: 'Could not send.', RU: 'Не удалось отправить.' },
  private: { EN: 'Health answers. Visible only to the owner, managers and the assigned technician.', RU: 'Ответы о здоровье. Видят только владелец, менеджеры и назначенный мастер.' },
  previewTitle: { EN: 'Detection check: first-time clients in the next 48 hours', RU: 'Проверка: первые визиты в ближайшие 48 часов' },
  previewHint: { EN: 'Nothing is sent from this list. It only shows who the system would treat as new.', RU: 'Из этого списка ничего не отправляется. Он только показывает, кого система считает новым клиентом.' },
  upcomingTitle: { EN: 'Upcoming first visits', RU: 'Ближайшие первые визиты' },
  mineTitle: { EN: 'My new clients: intake forms', RU: 'Мои новые клиенты: анкеты' },
  all: { EN: 'All', RU: 'Все' },
  unsigned: { EN: 'Not signed', RU: 'Не подписаны' },
  flagged: { EN: 'Check before service', RU: 'Проверить до услуги' },
};

export function it(lang: Language | null, key: keyof typeof DICT): string {
  const e = DICT[key];
  return lang === 'RU' ? e.RU : e.EN;
}

const ANSWER_VALUES: Record<string, { EN: string; RU: string }> = {
  YES: { EN: 'Yes', RU: 'Да' },
  NO: { EN: 'No', RU: 'Нет' },
  NOT_SURE: { EN: 'Not sure', RU: 'Не уверен(а)' },
  MANICURE: { EN: 'Manicure', RU: 'Маникюр' },
  PEDICURE: { EN: 'Pedicure', RU: 'Педикюр' },
  BOTH: { EN: 'Manicure and pedicure', RU: 'Маникюр и педикюр' },
  GOOGLE_SEARCH: { EN: 'Google search', RU: 'Поиск Google' },
  GOOGLE_MAPS: { EN: 'Google Maps', RU: 'Google Maps' },
  YELP: { EN: 'Yelp', RU: 'Yelp' },
  INSTAGRAM: { EN: 'Instagram', RU: 'Instagram' },
  TIKTOK: { EN: 'TikTok', RU: 'TikTok' },
  FRIEND_FAMILY: { EN: 'Friend or family', RU: 'Друзья или семья' },
  WALK_BY: { EN: 'Walked by', RU: 'Проходил(а) мимо' },
  CHATGPT: { EN: 'ChatGPT', RU: 'ChatGPT' },
  CLAUDE: { EN: 'Claude', RU: 'Claude' },
  PERPLEXITY: { EN: 'Perplexity', RU: 'Perplexity' },
  OTHER: { EN: 'Other', RU: 'Другое' },
};

const QUESTION_LABELS: Record<string, { EN: string; RU: string }> = {
  service: { EN: 'Service', RU: 'Услуга' },
  fungal: { EN: 'Fungal or contagious nail/skin condition', RU: 'Грибок или заразное заболевание ногтей/кожи' },
  skin: { EN: 'Open cuts, sores or irritated skin in the area', RU: 'Порезы, раны или раздражение кожи в зоне' },
  allergies: { EN: 'Allergies or reactions to nail products', RU: 'Аллергии или реакции на средства' },
  allergiesDetails: { EN: 'Allergy details', RU: 'Подробности об аллергии' },
  sensitivity: { EN: 'Sensitivity or pain during nail services', RU: 'Чувствительность или боль при услугах' },
  sensitivityDetails: { EN: 'Sensitivity details', RU: 'Подробности о чувствительности' },
  enhancements: { EN: 'Existing enhancements that may need removal', RU: 'Есть покрытие или наращивание, которое надо снять' },
  heardFrom: { EN: 'How they heard about us', RU: 'Как узнали о нас' },
  heardOther: { EN: 'Heard about us (other)', RU: 'Как узнали (другое)' },
  notes: { EN: 'Anything else for the technician', RU: 'Пожелания мастеру' },
};

export function answerLabel(lang: Language | null, key: string, fallback: string): string {
  const e = QUESTION_LABELS[key];
  return e ? (lang === 'RU' ? e.RU : e.EN) : fallback;
}

export function answerValue(lang: Language | null, key: string, value: string): string {
  if (key.endsWith('Details') || key === 'heardOther' || key === 'notes') return value;
  const e = ANSWER_VALUES[value];
  return e ? (lang === 'RU' ? e.RU : e.EN) : value;
}

export function badgeInfo(s: IntakeSummary, lang: Language | null): { label: string; className: string } {
  if (s.status === 'SIGNED') {
    return s.signedVia === 'STAFF_PAPER'
      ? { label: it(lang, 'badgePaper'), className: 'bg-sky-50 text-sky-800 ring-sky-200' }
      : { label: it(lang, 'badgeSigned'), className: 'bg-emerald-50 text-emerald-800 ring-emerald-200' };
  }
  if (s.status === 'SENT') return { label: it(lang, 'badgeSent'), className: 'bg-amber-50 text-amber-900 ring-amber-200' };
  return { label: it(lang, 'badgePending'), className: 'bg-zinc-100 text-zinc-700 ring-zinc-200' };
}
