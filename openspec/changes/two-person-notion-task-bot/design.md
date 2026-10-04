# Design

Keep the existing two configured Telegram identities and Business 1 bot. Model Anya and Alex as two actors with exact Notion select values. `/tasks` immediately shows the caller's open cards and a small inline menu for My / Anya / Alex / All; the named and combined views permit reading anyone's details, while mutation buttons appear only on the viewer's current tasks. All tasks are re-read before a callback write, so a stale card cannot act on a reassigned or completed page.

Apply the same morning, overdue and weekly routines to both people. On a successful state change, send the other person a concise notice with a details button. Do not send duplicate notices when a repeated callback finds the target state already set. Use neutral action labels and show the assignee on cards and details.

For a blocked reason, send a Telegram HTML ForceReply prompt whose linked task title carries the Notion page URL. The reply includes that bot message and its text-link entity; parse the page ID from the entity, then reload the page, validate ownership, and write the reason. This removes in-memory pending state that can disappear when the next poll lands on another backend replica. Commands and unrelated messages are never interpreted as a reason.

The Notion client validates that a page retrieved by ID belongs to the configured data source before exposing it for details or updates. Existing business-specific Telegram configuration and ShedLock remain unchanged. No migration, frontend route, or new public endpoint is needed.
No Flyway migration or SecurityConfig role change is involved; the bot remains restricted to the two configured private Telegram chats. No Square API call is added.
