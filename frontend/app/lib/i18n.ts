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
  kbArticleNotFound: {
    EN: "This article doesn't exist, or you don't have access to it.",
    RU: 'Эта статья не существует, или у вас нет к ней доступа.',
  },

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

  // Mandatory SOP acknowledgment gate
  sopAckTitle: { EN: 'Action required', RU: 'Требуется действие' },
  sopAckIntro: {
    EN: 'Please read the following and confirm before continuing.',
    RU: 'Пожалуйста, прочитайте следующее и подтвердите, прежде чем продолжить.',
  },
  sopAckScroll: {
    EN: 'Scroll to the end to enable confirmation',
    RU: 'Прокрутите до конца, чтобы подтвердить',
  },
  sopAckButton: {
    EN: 'I have read and agree to follow this SOP',
    RU: 'Я прочитал(а) и согласен(на) соблюдать этот регламент',
  },
  sopAckRemaining: { EN: 'to confirm', RU: 'к подтверждению' },
  sopWhatChanged: { EN: "What's changed in this version", RU: 'Что изменилось в этой версии' },
  sopNewVersionBadge: { EN: 'New version', RU: 'Новая версия' },
  sopContinueToRead: { EN: 'Continue to the full SOP →', RU: 'Перейти к полному регламенту →' },

  // First-sign-in "before you get started" explainer, shown once right after choosing a language.
  sopIntroTitle: { EN: 'Before you get started', RU: 'Прежде чем начать' },
  sopIntroBody1: {
    EN: 'We have SOPs (standard operating procedures) that we expect you to read and follow.',
    RU: 'У нас есть регламенты (SOPs), которые мы просим вас прочитать и соблюдать.',
  },
  sopIntroBody2: {
    EN: "So before getting started with the platform, we need you to read and acknowledge each one first.",
    RU: 'Поэтому прежде чем начать работу с платформой, нужно прочитать и подтвердить каждый из них.',
  },
  sopIntroBody3: {
    EN: "You'll also be able to revisit them later anytime, from the menu.",
    RU: 'Вы всегда сможете вернуться к ним позже — через меню.',
  },
  sopIntroBody4: {
    EN: "Each SOP has a version — once it's updated, we'll tell you what changed and ask you to review and acknowledge it again.",
    RU: 'У каждого регламента есть версия — когда она обновится, мы покажем, что изменилось, и попросим подтвердить снова.',
  },
  sopIntroFooter: {
    EN: 'Thank you! The next screen will have your first SOP to review.',
    RU: 'Спасибо! На следующем экране — первый регламент для проверки.',
  },
  sopIntroButton: { EN: "Let's get started →", RU: 'Начнём →' },

  // Manager home (/manager)
  mgrTitle: { EN: 'Manager', RU: 'Менеджер' },
  mgrSubtitle: {
    EN: 'Your tools — redos, knowledge base, and SOPs — plus provider retention. Ask the assistant (bottom-right) anytime.',
    RU: 'Ваши инструменты — переделки, база знаний и регламенты — плюс удержание клиентов. Ассистент (внизу справа) — в любой момент.',
  },
  mgrRedos: { EN: 'Redos', RU: 'Переделки' },
  mgrRedosDesc: {
    EN: 'Record a service redone by another provider.',
    RU: 'Зафиксируйте услугу, переделанную другим мастером.',
  },
  mgrKb: { EN: 'Knowledge base', RU: 'База знаний' },
  mgrKbDesc: { EN: 'Menus, scripts, and FAQ for the team.', RU: 'Меню, скрипты и FAQ для команды.' },
  mgrSops: { EN: 'SOPs', RU: 'Регламенты' },
  mgrSopsDesc: {
    EN: 'Policies and procedures to read and acknowledge.',
    RU: 'Политики и процедуры для ознакомления и подтверждения.',
  },

  // SOP admin (/sops/admin)
  sopAdminTitle: { EN: 'SOPs — admin', RU: 'Процедуры — администрирование' },
  sopAdminDesc: {
    EN: 'Author policy documents, publish versions, target an audience, and see who has acknowledged.',
    RU: 'Создавайте документы политик, публикуйте версии, выбирайте аудиторию и отслеживайте подтверждения.',
  },

  // Navigation menu (AdminMenu)
  navMenu: { EN: 'Menu', RU: 'Меню' },
  navLanguage: { EN: 'Language', RU: 'Язык' },
  navBusiness: { EN: 'Business', RU: 'Бизнес' },
  navGroupPayroll: { EN: 'Payroll tools', RU: 'Инструменты по зарплате' },
  navGroupStaff: { EN: 'Staff', RU: 'Персонал' },
  navGroupIntegrations: { EN: 'Business integrations', RU: 'Интеграции бизнеса' },
  navGroupSettings: { EN: 'Settings', RU: 'Настройки' },
  navSalaryReport: { EN: 'Salary report', RU: 'Отчёт по зарплате' },
  navRevenue: { EN: 'Revenue', RU: 'Выручка' },
  navMarketing: { EN: 'Marketing', RU: 'Маркетинг' },
  navRetention: { EN: 'Retention', RU: 'Удержание' },
  navPrepaid: { EN: 'Prepaid', RU: 'Предоплата' },
  navOwnerComps: { EN: 'Owner comps', RU: 'Услуги владельцу' },
  navManualAdjustments: { EN: 'Manual adjustments', RU: 'Ручные корректировки' },
  navUsers: { EN: 'Users', RU: 'Пользователи' },
  navStaffDocuments: { EN: 'Staff Documents', RU: 'Документы сотрудников' },
  navReviews: { EN: 'Reviews', RU: 'Отзывы' },
  navTelegramSettings: { EN: 'Telegram Notifications', RU: 'Уведомления в Telegram' },
  navSmsSettings: { EN: 'SMS Notifications', RU: 'Уведомления по SMS' },
  navSquareSettings: { EN: 'Square Connection', RU: 'Подключение Square' },
  navBusinessSettings: { EN: 'Business Settings', RU: 'Настройки бизнеса' },
  navBusinesses: { EN: 'Businesses', RU: 'Бизнесы' },
  navOnboarding: { EN: 'Getting Started', RU: 'Начало работы' },
  navDashboard: { EN: 'Dashboard', RU: 'Панель' },
  navMyPay: { EN: 'My pay', RU: 'Моя зарплата' },
  navMyTime: { EN: 'My time', RU: 'Моё время' },
  navManagerTime: { EN: 'Manager time', RU: 'Время менеджеров' },
  navMyDocuments: { EN: 'My Documents', RU: 'Мои документы' },

  // My Documents (provider/manager self-service, read-only)
  myDocumentsTitle: { EN: 'My Documents', RU: 'Мои документы' },
  myDocumentsSubtitle: {
    EN: 'Your contracts, licenses, and other documents on file. View and download only — ask the owner for any changes.',
    RU: 'Ваши контракты, лицензии и другие документы. Только просмотр и скачивание — по любым изменениям обращайтесь к владельцу.',
  },
  myDocumentsEmpty: { EN: 'No documents on file yet.', RU: 'Пока нет документов.' },
  myDocumentsExpiresOn: { EN: 'Expires', RU: 'Истекает' },
  myDocumentsDownload: { EN: 'Download', RU: 'Скачать' },
  myDocumentsStatusOk: { EN: 'Valid', RU: 'Действителен' },
  myDocumentsStatusExpiringSoon: { EN: 'Expiring soon', RU: 'Истекает скоро' },
  myDocumentsStatusExpired: { EN: 'Expired', RU: 'Истёк' },

  // Manager time tracking
  timeMyTitle: { EN: 'My time', RU: 'Моё время' },
  timeMySubtitle: {
    EN: 'Clock in and out, or add a shift by hand. Your hours add up for the month.',
    RU: 'Отмечайте начало и конец смены или добавьте её вручную. Часы суммируются за месяц.',
  },
  timeClockIn: { EN: 'Clock in', RU: 'Начать смену' },
  timeClockOut: { EN: 'Clock out', RU: 'Закончить смену' },
  timeClockedInSince: { EN: 'Clocked in since', RU: 'Смена с' },
  timeMonthTotal: { EN: 'This month', RU: 'За месяц' },
  timeFirstHalf: { EN: '1–15', RU: '1–15' },
  timeSecondHalf: { EN: '16–end', RU: '16–конец' },
  timeRateUnset: {
    EN: 'Your hourly rate isn’t set yet — ask the owner. Hours are still being tracked.',
    RU: 'Ваша ставка ещё не установлена — спросите владельца. Часы всё равно учитываются.',
  },
  timeAddShift: { EN: 'Add a shift', RU: 'Добавить смену' },
  timeDate: { EN: 'Date', RU: 'Дата' },
  timeStart: { EN: 'Start', RU: 'Начало' },
  timeEnd: { EN: 'End', RU: 'Конец' },
  timeNote: { EN: 'Note (optional)', RU: 'Заметка (необязательно)' },
  timeAdd: { EN: 'Add', RU: 'Добавить' },
  timeEdit: { EN: 'Edit', RU: 'Изменить' },
  timeDelete: { EN: 'Delete', RU: 'Удалить' },
  timeSave: { EN: 'Save', RU: 'Сохранить' },
  timeCancel: { EN: 'Cancel', RU: 'Отмена' },
  timeNoEntries: { EN: 'No shifts logged this month yet.', RU: 'В этом месяце ещё нет смен.' },
  timeShifts: { EN: 'Shifts', RU: 'Смены' },
  timeEndBeforeStart: { EN: 'End time must be after start time.', RU: 'Время окончания должно быть позже времени начала.' },
  timeDuration: { EN: 'Duration', RU: 'Длительность' },
  timeCheckAmPm: {
    EN: 'That looks like a {dur} shift ({start} – {end}). Double-check AM/PM before saving.',
    RU: 'Похоже, это смена на {dur} ({start} – {end}). Проверьте АМ/PM перед сохранением.',
  },
  timeConfirmAmPm: { EN: 'Yes, this time is correct', RU: 'Да, это время верное' },
  // Owner payroll view
  timeOwnerTitle: { EN: 'Manager time', RU: 'Время менеджеров' },
  timeOwnerSubtitle: {
    EN: 'Hours each manager logged, and their pay. Set an hourly rate to compute pay.',
    RU: 'Часы, отмеченные каждым менеджером, и их оплата. Укажите ставку, чтобы рассчитать оплату.',
  },
  timeManager: { EN: 'Manager', RU: 'Менеджер' },
  timeRate: { EN: 'Rate ($/hr)', RU: 'Ставка ($/ч)' },
  timePay: { EN: 'Pay', RU: 'Оплата' },
  timeHours: { EN: 'Hours', RU: 'Часы' },
  timeClockedInNow: { EN: 'On the clock now', RU: 'Сейчас на смене' },
  timeSetRatePlaceholder: { EN: 'Set rate', RU: 'Ставка' },
  timeAllManagersTotal: { EN: 'All managers, this month', RU: 'Все менеджеры, за месяц' },
  timeMissingRateNote: {
    EN: "doesn't include managers without a rate set",
    RU: 'без учёта менеджеров без установленной ставки',
  },
  timeNoManagers: { EN: 'No managers yet.', RU: 'Пока нет менеджеров.' },

  // Owner daily-schedule timeline (anomaly detection)
  scheduleTabSummary: { EN: 'Summary', RU: 'Итого' },
  scheduleTabSchedule: { EN: 'Schedule', RU: 'График' },
  scheduleSubtitle: {
    EN: 'Daily coverage for each manager. Expected: {start}–{end}, with about {overlap} of handoff overlap.',
    RU: 'Ежедневное покрытие по каждому менеджеру. Ожидается: {start}–{end}, с пересечением ~{overlap} для передачи смены.',
  },
  scheduleAnomaliesOnly: { EN: 'Show only days needing review', RU: 'Показывать только дни, требующие проверки' },
  scheduleAllClear: { EN: 'No anomalies this month — every day looks normal.', RU: 'За этот месяц аномалий нет — все дни выглядят нормально.' },
  scheduleNoDaysMatch: { EN: 'No days need review this month.', RU: 'В этом месяце нет дней, требующих проверки.' },
  scheduleCoverage: { EN: 'Coverage', RU: 'Покрытие' },
  scheduleOverlap: { EN: 'Overlap', RU: 'Пересечение' },
  scheduleOngoing: { EN: 'ongoing', RU: 'идёт сейчас' },
  scheduleNoShiftsDay: { EN: 'No shifts logged', RU: 'Смены не отмечены' },

  flag_gap_in_coverage: { EN: 'Coverage gap', RU: 'Пробел в покрытии' },
  flag_no_overlap: { EN: 'No handoff overlap', RU: 'Нет пересечения для передачи' },
  flag_overlap_low: { EN: 'Overlap shorter than usual', RU: 'Пересечение короче обычного' },
  flag_overlap_high: { EN: 'Overlap longer than usual', RU: 'Пересечение длиннее обычного' },
  flag_no_shifts: { EN: 'No shifts logged', RU: 'Нет отмеченных смен' },
  flag_start_way_off: { EN: 'Start time looks wrong — check AM/PM', RU: 'Время начала выглядит неверным — проверьте АМ/PM' },
  flag_start_late: { EN: 'Started later than usual', RU: 'Начало позже обычного' },
  flag_start_early: { EN: 'Started earlier than usual', RU: 'Начало раньше обычного' },
  flag_end_way_off: { EN: 'End time looks wrong — check AM/PM', RU: 'Время окончания выглядит неверным — проверьте АМ/PM' },
  flag_end_late: { EN: 'Ended later than usual', RU: 'Окончание позже обычного' },
  flag_end_early: { EN: 'Ended earlier than usual', RU: 'Окончание раньше обычного' },
  flag_too_short: { EN: 'Unusually short shift', RU: 'Необычно короткая смена' },
  flag_too_long: { EN: 'Unusually long shift', RU: 'Необычно длинная смена' },
  flag_still_open: { EN: 'Forgot to clock out?', RU: 'Забыли завершить смену?' },

  // Provider pay (/me)
  meNoActivityMonth: { EN: 'No activity for this month.', RU: 'Нет активности за этот месяц.' },
  meFellback: {
    EN: 'No activity yet for {cur} — showing {shown}. Use {mon} → above once your new month has sales.',
    RU: 'Пока нет активности за {cur} — показываем {shown}. Нажмите {mon} → выше, когда в новом месяце появятся продажи.',
  },
  meMonthToYou: { EN: 'Month → you', RU: 'Месяц → вам' },
  meCashToSalon: { EN: 'Cash → salon', RU: 'Наличные → салон' },
  meMainServices: { EN: 'Main services', RU: 'Основные услуги' },
  meTier: { EN: 'Tier', RU: 'Тариф' },
  meTotalServices: { EN: 'Total services', RU: 'Всего услуг' },
  meCard: { EN: 'Card', RU: 'Карта' },
  meCash: { EN: 'Cash', RU: 'Наличные' },
  meDiscountCovered: { EN: 'discount covered', RU: 'покрытая скидка' },
  meTips: { EN: 'Tips (after fee)', RU: 'Чаевые (после комиссии)' },
  meToYouZelle: { EN: '→ You (Zelle)', RU: '→ Вам (Zelle)' },
  meInclBonus: { EN: 'incl. month 50/50 bonus', RU: 'вкл. месячный бонус 50/50' },
  meInclRebate: { EN: 'incl. tier cash rebate', RU: 'вкл. возврат наличных по тарифу' },
  mePeriodEnd: { EN: '16–end', RU: '16–конец' },
  meCutoffTip: {
    EN: 'A "main service" is one with a gross of {amount} or higher (counts toward the 50/50 tier). Add-ons below that aren’t counted.',
    RU: '«Основная услуга» — это услуга с суммой {amount} и выше (учитывается в тарифе 50/50). Дополнения ниже этой суммы не учитываются.',
  },
  meDiscTip: {
    EN: 'The salon absorbs discounts — your pay is on the full menu price, so this discount didn’t reduce what you earned here.',
    RU: 'Салон покрывает скидки — ваша оплата рассчитывается по полной цене из меню, поэтому эта скидка не уменьшила ваш заработок.',
  },
  meBonusReached: { EN: '50/50 tier reached — month bonus {total}', RU: 'Достигнут тариф 50/50 — месячный бонус {total}' },
  meBonusNoteTip: {
    EN: 'You hit the 50/50 tier this month, so you earn the higher rate on your whole month — both periods (1–15 and 16–end), not just one: {bonus} extra on your card (the uplift on {monthCard}){rebate}, {total} in total. It’s settled at month close, so it lands inside your 16–end total below.',
    RU: 'Вы достигли тарифа 50/50 в этом месяце, поэтому получаете повышенную ставку за весь месяц — за оба периода (1–15 и 16–конец), а не за один: {bonus} дополнительно на карту (надбавка к {monthCard}){rebate}, всего {total}. Расчёт при закрытии месяца, поэтому сумма входит в ваш итог 16–конец ниже.',
  },
  meBonusNoteRebate: {
    EN: ' and a {rebate} rebate on the cash you hand back to the salon',
    RU: ' и возврат {rebate} с наличных, которые вы сдаёте салону',
  },
  meBonusSubRebate: { EN: '{bonus} on card + {rebate} cash rebate · ', RU: '{bonus} на карту + {rebate} возврат наличными · ' },
  meBonusSubTail: {
    EN: 'covers the whole month, paid inside your 16–end total below.',
    RU: 'покрывает весь месяц, выплачивается в вашем итоге 16–конец ниже.',
  },
  meNeedsNote: { EN: '{n} · note needed', RU: '{n} · нужна заметка' },
  meNeedsNoteTip: {
    EN: '{n} appointment(s) need a note added in Square',
    RU: '{n} приём(ов) требуют примечания в Square',
  },
  meApproveBlocked: {
    EN: '{n} appointment(s) need a note before you can approve. Review the list above, or contact management.',
    RU: '{n} приём(ов) требуют примечания перед подтверждением. Просмотрите список выше или свяжитесь с руководством.',
  },
  meServiceBreakdown: { EN: 'Service breakdown', RU: 'Разбивка по услугам' },
  meServiceBreakdownDesc: {
    EN: 'Every service this period, with discounts and cash notes, so you can check your numbers.',
    RU: 'Каждая услуга за период, со скидками и заметками по наличным, чтобы вы могли проверить свои цифры.',
  },
  meNoShows: { EN: 'No-shows', RU: 'Неявки' },
  meNoShowsDesc: {
    EN: 'Your no-show appointments this month. When the salon collects the $25 cancellation fee, it’s paid to you in full — these credits are already in your total above.',
    RU: 'Ваши приёмы с неявкой за этот месяц. Когда салон взимает штраф $25 за отмену, он полностью выплачивается вам — эти начисления уже в вашем итоге выше.',
  },
  meNoShowFeeCredited: { EN: 'No-show fees credited to you', RU: 'Штрафы за неявку, начисленные вам' },
  meFeePaid: { EN: 'fee paid', RU: 'штраф оплачен' },
  meNoFee: { EN: 'no fee collected', RU: 'штраф не взят' },

  // Settlement feedback (approve / request correction)
  fbYourResponse: { EN: 'Your response', RU: 'Ваш ответ' },
  fbApproved: { EN: 'approved', RU: 'подтверждено' },
  fbChangesRequested: { EN: 'changes requested', RU: 'запрошены изменения' },
  fbPlaceholder: {
    EN: 'Optional note (helpful when requesting a correction)',
    RU: 'Необязательная заметка (полезно при запросе исправления)',
  },
  fbApprove: { EN: 'Approve', RU: 'Подтвердить' },
  fbRequestCorrection: { EN: 'Request correction', RU: 'Запросить исправление' },
  fbFailed: { EN: 'Failed to submit', RU: 'Не удалось отправить' },

  // Sync badge
  syncSynced: { EN: 'Synced with Square', RU: 'Синхронизировано со Square' },
  syncTip: {
    EN: 'Pulled from Square and cached briefly for speed. The time is when it was last fetched — hit Sync to pull fresh.',
    RU: 'Данные получены из Square и кратко кэшируются для скорости. Время — момент последнего получения; нажмите «Синхронизировать», чтобы обновить.',
  },

  // SOP list (read + acknowledge)
  sopNone: { EN: 'No SOPs yet.', RU: 'Пока нет регламентов.' },
  sopArticleNotFound: {
    EN: "This SOP doesn't exist, or you don't have access to it.",
    RU: 'Этот регламент не существует, или у вас нет к нему доступа.',
  },
  sopAcknowledged: { EN: 'Acknowledged', RU: 'Подтверждено' },
  sopOpenFirst: { EN: 'Open the SOP first', RU: 'Сначала откройте регламент' },

  // Collapsible period card (service breakdown)
  cbServiceCount: { EN: '{n} services', RU: 'услуг: {n}' },
  cbGross: { EN: 'gross', RU: 'брутто' },
  cbDiscounts: { EN: 'discounts', RU: 'скидки' },
  cbTips: { EN: 'tips', RU: 'чаевые' },
  cbShowBreakdown: { EN: 'Show breakdown', RU: 'Показать разбивку' },
  cbHideBreakdown: { EN: 'Hide breakdown', RU: 'Скрыть разбивку' },

  // Redos (/admin/redos) — manager task
  redoDesc: {
    EN: 'When a customer is unhappy and has a service redone by a different provider, record it here. The service’s commission moves from the original provider (on the original date) to the redo provider (on the redo date) — they show as REDO lines in the reports.',
    RU: 'Когда клиент недоволен и услугу переделывает другой мастер, зафиксируйте это здесь. Комиссия за услугу переходит от изначального мастера (на изначальную дату) к мастеру переделки (на дату переделки) — в отчётах они отображаются как строки REDO.',
  },
  redoOriginalProvider: { EN: 'Original provider', RU: 'Изначальный мастер' },
  redoOriginalDate: { EN: 'Original date', RU: 'Изначальная дата' },
  redoProvider: { EN: 'Redo provider', RU: 'Мастер переделки' },
  redoDate: { EN: 'Redo date', RU: 'Дата переделки' },
  redoServiceAmount: { EN: 'Service amount', RU: 'Сумма услуги' },
  redoServiceOptional: { EN: 'Service (optional)', RU: 'Услуга (необязательно)' },
  redoSelect: { EN: 'Select…', RU: 'Выберите…' },
  redoAdd: { EN: 'Add redo', RU: 'Добавить переделку' },
  redoAdding: { EN: 'Adding…', RU: 'Добавление…' },
  redoNone: { EN: 'No redos recorded.', RU: 'Переделок пока нет.' },
  redoFrom: { EN: 'From', RU: 'От' },
  redoTo: { EN: 'To', RU: 'Кому' },
  redoDelete: { EN: 'Delete', RU: 'Удалить' },
  redoColFrom: { EN: 'From (original)', RU: 'От (изначальный)' },
  redoColTo: { EN: 'To (redo)', RU: 'Кому (переделка)' },
  redoAmount: { EN: 'Amount', RU: 'Сумма' },
  redoService: { EN: 'Service', RU: 'Услуга' },
  redoErrSameProvider: { EN: 'The redo provider must differ from the original.', RU: 'Мастер переделки должен отличаться от изначального.' },
  redoErrCreate: { EN: 'Could not create the redo.', RU: 'Не удалось создать переделку.' },
  redoErrCreateFallback: { EN: 'Failed to create', RU: 'Не удалось создать' },
  redoErrDelete: { EN: 'Could not delete.', RU: 'Не удалось удалить.' },
  redoConfirmDelete: { EN: 'Delete this redo ({from} → {to})?', RU: 'Удалить эту переделку ({from} → {to})?' },

  // Ads report (owner/marketing/ads-report) — block titles + info tooltips
  adsRevenueTitle: { EN: 'Revenue', RU: 'Выручка' },
  adsRevenueInfo: {
    EN: 'Money from ads-driven customers: already collected, plus what they still have booked — split into this period and later.',
    RU: 'Деньги от клиентов, привлечённых рекламой: уже собранные, плюс то, что у них ещё запланировано — с разбивкой на этот период и на будущее.',
  },
  adsRoiTitle: { EN: 'ROI', RU: 'ROI' },
  adsRoiInfo: {
    EN: 'Return on ad spend: Realized ROAS uses only money already collected; Total ROAS and ROI% also count what these customers still have booked.',
    RU: 'Окупаемость расходов на рекламу: Realized ROAS учитывает только уже полученные деньги; Total ROAS и ROI % также учитывают то, что эти клиенты ещё забронировали.',
  },
  adsAdSpend: { EN: 'Ad spend', RU: 'Расходы на рекламу' },
  adsBookingsTitle: { EN: 'Bookings', RU: 'Записи' },
  adsBookingsInfo: {
    EN: 'What happened to every appointment dated in this period for these ads-driven customers: completed, cancelled/no-show, or still upcoming — split into this period and later.',
    RU: 'Что произошло с каждой записью на этот период у клиентов, привлечённых рекламой: завершена, отменена/неявка, или ещё предстоит — с разбивкой на этот период и на будущее.',
  },
  adsCustomersTitle: { EN: 'Customers', RU: 'Клиенты' },
  adsCustomersInfo: {
    EN: 'The same breakdown as Bookings, but counting distinct people instead of appointments — someone with two visits in one bucket counts once here. A customer who falls into more than one bucket (e.g. one visit completed, another still upcoming) is counted in each, so these four don’t add up to a unique headcount.',
    RU: 'Та же разбивка, что и в Записях, но считаются уникальные люди, а не записи — человек с двумя визитами в одной категории здесь считается один раз. Клиент, попадающий в несколько категорий одновременно (например, один визит завершён, другой ещё предстоит), учитывается в каждой из них, поэтому сумма этих четырёх чисел — не уникальное количество людей.',
  },
} satisfies Record<string, Record<Language, string>>;

/** Translate a UI key to the given language; English when the language is unset. */
export function t(lang: Language | null, key: keyof typeof STRINGS): string {
  return STRINGS[key][lang ?? 'EN'];
}

/** Like {@link t}, but replaces {placeholders} with the given values (e.g. {n}, {amount}). */
export function tf(
  lang: Language | null,
  key: keyof typeof STRINGS,
  vars: Record<string, string | number>,
): string {
  let s: string = STRINGS[key][lang ?? 'EN'];
  for (const [k, v] of Object.entries(vars)) s = s.split(`{${k}}`).join(String(v));
  return s;
}

const MONTHS_LONG: Record<Language, string[]> = {
  EN: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
  RU: ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'],
};
const MONTHS_ABBR: Record<Language, string[]> = {
  EN: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
  RU: ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'],
};

/** Localized full month name for a 0-based index. */
export function monthName(lang: Language | null, idx0: number): string {
  return MONTHS_LONG[lang ?? 'EN'][((idx0 % 12) + 12) % 12];
}

/** Localized 3-letter month abbreviation for a 0-based index. */
export function monthShort(lang: Language | null, idx0: number): string {
  return MONTHS_ABBR[lang ?? 'EN'][((idx0 % 12) + 12) % 12];
}

const WEEKDAYS_ABBR: Record<Language, string[]> = {
  EN: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'],
  RU: ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'],
};

/** Localized 3-letter weekday abbreviation for a JS Date.getDay() index (0 = Sunday). */
export function weekdayShort(lang: Language | null, dow0Sunday: number): string {
  return WEEKDAYS_ABBR[lang ?? 'EN'][((dow0Sunday % 7) + 7) % 7];
}

/** Localized label for a manager-schedule anomaly flag code (see ManagerTimeService, backend) —
 * falls back to the raw code if a translation is ever missing, so an unrecognized future flag
 * degrades to something inspectable rather than crashing. */
export function flagLabel(lang: Language | null, code: string): string {
  const entry = (STRINGS as Record<string, Record<Language, string> | undefined>)[`flag_${code}`];
  return entry ? entry[lang ?? 'EN'] : code;
}

/** Picks the Russian variant of a user-authored bilingual field (SOP/KB title, body, etc.) when
 * the viewer's language is Russian and a translation was actually entered; falls back to the
 * English variant otherwise — the same "null/blank falls back to English" rule those fields
 * already use for body/changeNote (see SopArticleBody, KbArticleBody). */
export function localized(lang: Language | null, en: string, ru: string | null): string {
  return lang === 'RU' && ru && ru.trim() ? ru : en;
}
