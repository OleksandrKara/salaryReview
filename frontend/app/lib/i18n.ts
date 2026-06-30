import type { Language } from './types';

// Lightweight UI strings for the KB and SOP page headers, keyed by language. English is the default
// and the fallback (used when a user hasn't picked a language). Kept as a small typed dictionary
// rather than a full i18n framework — only these pages are localized so far.
const STRINGS = {
  back: { EN: '← Back', RU: '← Назад' },
  backReports: { EN: '← Reports', RU: '← Отчёты' },
  logout: { EN: 'Log out', RU: 'Выйти' },

  // Knowledge base (/kb)
  kbTitle: { EN: 'Knowledge base', RU: 'База знаний' },
  kbDesc: { EN: 'Service menus, scripts, and FAQ for the team.', RU: 'Меню услуг, скрипты и FAQ для команды.' },
  kbDescEdit: {
    EN: ' Edit here; sync to the assistant from the RAG admin page.',
    RU: ' Редактируйте здесь; синхронизация с ассистентом — на странице администрирования RAG.',
  },
  kbDescReadOnly: { EN: ' Read-only.', RU: ' Только для чтения.' },

  // SOPs (/sops)
  sopTitle: { EN: 'Standard operating procedures', RU: 'Стандартные операционные процедуры' },
  sopOwnerDescPre: { EN: 'You author SOPs on the ', RU: 'Вы создаёте процедуры на ' },
  sopOwnerDescLink: { EN: 'admin page', RU: 'странице администрирования' },
  sopOwnerDescPost: {
    EN: '. Below is everything as staff see it.',
    RU: '. Ниже — всё, как это видят сотрудники.',
  },
  sopStaffDesc: {
    EN: 'Open each SOP and acknowledge that you have read and agree to follow it.',
    RU: 'Откройте каждую процедуру и подтвердите, что вы прочитали её и согласны соблюдать.',
  },

  // SOP admin (/sops/admin)
  sopAdminTitle: { EN: 'SOPs — admin', RU: 'Процедуры — администрирование' },
  sopAdminDesc: {
    EN: 'Author policy documents, publish versions, target an audience, and see who has acknowledged.',
    RU: 'Создавайте документы политик, публикуйте версии, выбирайте аудиторию и отслеживайте подтверждения.',
  },
} satisfies Record<string, Record<Language, string>>;

/** Translate a UI key to the given language; English when the language is unset. */
export function t(lang: Language | null, key: keyof typeof STRINGS): string {
  return STRINGS[key][lang ?? 'EN'];
}
