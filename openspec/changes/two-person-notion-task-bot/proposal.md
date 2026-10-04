# Two-person Notion task bot

The Telegram task bot currently treats Anya as the only executor. Alex can read her tasks but cannot see or update the tasks assigned to him in the same Notion database. Make both people first-class users of the bot, with a personal task list, explicit Anya/Alex/combined views, owner-only task actions, and immediate counterpart notices when either person starts, completes, blocks, or reschedules work.

Notion remains the source of truth. The live `Исполнитель` select has `Аня`, `Алекс`, and `Claude`; only the two human values belong in these Telegram views. Before every write, reload the page and verify both its data-source parent and its current assignee. The blocked-reason flow must survive blue/green polling without relying on local process memory.

Verify with focused service/Notion tests and the backend CI suite. The production Notion schema was inspected read-only; no live task or Telegram message is changed by tests.

## Non-goals

Changing task ownership in Telegram, creating tasks, altering `Claude` tasks, adding more users, and redesigning the Notion database.
